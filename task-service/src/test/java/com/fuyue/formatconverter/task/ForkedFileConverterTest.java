package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ForkedFileConverterTest {
    @TempDir Path temp;

    @Test void convertsInAnIndependentJvm() throws Exception {
        Path input = temp.resolve("input.txt");
        Files.writeString(input, "worker process text", StandardCharsets.UTF_8);
        Path output = temp.resolve("output.docx");
        ForkedFileConverter converter = new ForkedFileConverter(new TextToDocxConverter().route(),
                workerCommand(ConversionWorkerMain.class), "", Duration.ofSeconds(10));

        ConversionOutput converted = converter.convert(
                new ConversionInput("input.txt", "text/plain", Files.size(input), input),
                temp.resolve("work"), output, ParseLimits.defaults(), (stage, progress) -> { });

        assertEquals("input.docx", converted.outputName());
        assertTrue(Files.size(output) > 0);
        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            assertTrue(word.getParagraphs().stream().anyMatch(p -> p.getText().contains("worker process text")));
        }
    }

    @Test void reportsWorkerCrashWhenProcessExitsWithoutResponse() throws Exception {
        Path input = temp.resolve("crash.txt");
        Files.writeString(input, "crash", StandardCharsets.UTF_8);
        ForkedFileConverter converter = new ForkedFileConverter(new TextToDocxConverter().route(),
                workerCommand(ExitWithoutResponseMain.class), "", Duration.ofSeconds(10));

        ConversionFailureException error = assertThrows(ConversionFailureException.class, () -> converter.convert(
                new ConversionInput("crash.txt", "text/plain", Files.size(input), input),
                temp.resolve("crash-work"), temp.resolve("crash.docx"), ParseLimits.defaults(),
                (stage, progress) -> { }));

        assertEquals("WORKER_CRASHED", error.code());
        assertTrue(error.getMessage().contains("exit=17"));
    }

    @Test void acceptsNumberedMultiPageZipFromIndependentJvmWithinOutputDirectory() throws Exception {
        Path input = temp.resolve("pages.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.addPage(new PDPage());
            pdf.save(input.toFile());
        }
        Path requestedOutput = temp.resolve("pages.png");
        ForkedFileConverter converter = new ForkedFileConverter(new PdfToPngConverter().route(),
                workerCommand(ConversionWorkerMain.class), "", Duration.ofSeconds(20));

        ConversionOutput converted = converter.convert(
                new ConversionInput("pages.pdf", "application/pdf", Files.size(input), input),
                temp.resolve("pdf-work"), requestedOutput, ParseLimits.defaults(), (stage, progress) -> { });

        assertEquals("pages-pages.zip", converted.outputName());
        assertEquals(requestedOutput.getParent(), converted.path().getParent());
        assertNotEquals(requestedOutput, converted.path());
        try (ZipFile zip = new ZipFile(converted.path().toFile(), StandardCharsets.UTF_8)) {
            assertEquals(2, zip.size());
        }
    }

    @Test void returnsMultiSheetCsvZipFromIndependentJvmAtExpectedWorkerPath() throws Exception {
        Path input = temp.resolve("multi.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("one").createRow(0).createCell(0).setCellValue("一");
            workbook.createSheet("two").createRow(0).createCell(0).setCellValue("二");
            try (var out = Files.newOutputStream(input)) { workbook.write(out); }
        }
        Path output = temp.resolve("output.csv");
        ForkedFileConverter converter = new ForkedFileConverter(new XlsxToCsvConverter().route(),
                workerCommand(ConversionWorkerMain.class), "", Duration.ofSeconds(15));

        ConversionOutput converted = converter.convert(
                new ConversionInput("multi.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        Files.size(input), input),
                temp.resolve("xlsx-work"), output, ParseLimits.defaults(), (stage, progress) -> { });

        assertEquals("multi-sheets.zip", converted.outputName());
        try (ZipFile zip = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(2, zip.size());
        }
    }

    @Test void passesPdfUtilityOptionsIntoIndependentJvm() throws Exception {
        Path input = temp.resolve("watermark-pages.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.addPage(new PDPage());
            pdf.save(input.toFile());
        }
        Path output = temp.resolve("watermarked.pdf");
        ForkedFileConverter converter = new ForkedFileConverter(new PdfWatermarkConverter().route(),
                workerCommand(ConversionWorkerMain.class), "", Duration.ofSeconds(20));
        ConversionOptions options = ConversionOptions.fromRequest(null, "INTERNAL-ONLY", 0.25d, 20d,
                "center", false, "2", "#888888");

        converter.convert(new ConversionInput("watermark-pages.pdf", "application/pdf", Files.size(input), input,
                        options), temp.resolve("watermark-worker"), output, ParseLimits.defaults(),
                (stage, progress) -> { });

        try (PDDocument pdf = Loader.loadPDF(output.toFile())) {
            PDFTextStripper text = new PDFTextStripper();
            text.setStartPage(1); text.setEndPage(1);
            assertFalse(text.getText(pdf).contains("INTERNAL-ONLY"));
            text.setStartPage(2); text.setEndPage(2);
            String secondPage = text.getText(pdf);
            assertTrue(secondPage.replaceAll("\\s+", "").contains("INTERNAL-ONLY"),
                    "second page text was: " + secondPage);
        }
    }

    @Test void taskTimeoutTerminatesWorkerAndReturnsDedicatedCode() throws Exception {
        ForkedFileConverter converter = new ForkedFileConverter(new TextToDocxConverter().route(),
                workerCommand(SleepingMain.class), "", Duration.ofSeconds(10));
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("timeout-data"), 1, 2,
                Duration.ofSeconds(1), Duration.ofHours(1), ParseLimits.defaults());
        byte[] payload = "timeout".getBytes(StandardCharsets.UTF_8);
        long started = System.nanoTime();

        try (ConversionTaskService service = new ConversionTaskService(config, List.of(converter))) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("timeout.txt", "text/plain",
                    payload.length, () -> new ByteArrayInputStream(payload))), DocumentFormat.DOCX);
            TaskSnapshot finished = await(service, created.taskId());

            assertEquals(TaskStatus.FAILED, finished.status());
            assertEquals("CONVERSION_TIMEOUT", finished.files().get(0).errorCode());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(8)) < 0);
        }
    }

    @Test void selectsEachImagesOwnWorkerRouteWhenMergingMixedFormats() throws Exception {
        List<FileConverter> converters = List.of(DocumentFormat.PNG, DocumentFormat.JPG).stream()
                .map(format -> (FileConverter) new ForkedFileConverter(new ImageToPdfConverter(format).route(),
                        workerCommand(ConversionWorkerMain.class), "", Duration.ofSeconds(10))).toList();
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("mixed-worker-data"), 1, 2,
                Duration.ofSeconds(20), Duration.ofHours(1), ParseLimits.defaults());
        byte[] png = solidImage("png", 80, 40, Color.RED);
        byte[] jpeg = solidImage("jpeg", 40, 80, Color.BLUE);
        try (ConversionTaskService service = new ConversionTaskService(config, converters)) {
            TaskSnapshot created = service.createTask(List.of(
                    new UploadPayload("first.png", "image/png", png.length, () -> new ByteArrayInputStream(png)),
                    new UploadPayload("second.jpeg", "image/jpeg", jpeg.length, () -> new ByteArrayInputStream(jpeg))),
                    DocumentFormat.PDF);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertTrue(finished.files().stream().allMatch(TaskFileResult::success), finished.files().toString());
            assertEquals(List.of(DocumentFormat.PNG, DocumentFormat.JPG),
                    finished.files().stream().map(TaskFileResult::sourceFormat).toList());
            try (PDDocument pdf = Loader.loadPDF(service.download(created.taskId()).path().toFile())) {
                assertEquals(2, pdf.getNumberOfPages());
                assertEquals(60d, pdf.getPage(0).getMediaBox().getWidth(), 0.1d);
                assertEquals(30d, pdf.getPage(1).getMediaBox().getWidth(), 0.1d);
            }
        }
    }

    @Test void retainsAutoDiscoveredBundledOcrWhenTheWorkerChangesItsWorkingDirectory() throws Exception {
        for (String discovery : List.of("format.converter.app.home", "user.dir", "jpackage.app-path")) {
            runOcrParent(discovery, Map.of(), "BUNDLED-WORKER");
        }
    }

    @Test void preservesExplicitOcrDisableWhenPassingBundleHomeToWorker() throws Exception {
        runOcrParent("format.converter.app.home", Map.of("FORMAT_CONVERTER_OCR_ENABLED", "false"), null);
    }

    @Test void preservesAnExplicitOcrEngineInsteadOfReplacingItWithTheBundle() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path custom = temp.resolve("explicit engine/tesseract");
        writeOcrBinary(custom, "CUSTOM-WORKER");
        runOcrParent("format.converter.app.home",
                Map.of("FORMAT_CONVERTER_TESSERACT_BINARY", custom.toString()), "CUSTOM-WORKER");
    }

    private void runOcrParent(String discovery, Map<String, String> overrides, String expected) throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path scenario = Files.createTempDirectory(temp, "ocr-parent-");
        Path appHome = scenario.resolve("Fuyue Convert installation");
        Path binary = appHome.resolve("app/ocr/bin/tesseract");
        writeOcrBinary(binary, "BUNDLED-WORKER");
        Files.createDirectories(appHome.resolve("app/ocr/tessdata"));
        Path outside = Files.createDirectories(scenario.resolve("external working directory"));
        Path input = scenario.resolve("input.png"), output = scenario.resolve("result.txt");
        Files.write(input, solidImage("png", 200, 100, Color.WHITE));
        Path workerDirectory = scenario.resolve("isolated worker directory");
        List<String> command = new ArrayList<>(workerCommand(OcrParentMain.class));
        command.addAll(List.of(discovery, appHome.toString(), input.toString(),
                workerDirectory.toString(), output.toString(), expected == null ? "DISABLED" : expected));
        Path log = scenario.resolve("parent.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(outside.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        // Isolate discovery from the developer machine and exercise explicit
        // environment settings through both real JVM process boundaries.
        builder.environment().keySet().removeIf(key -> key.startsWith("FORMAT_CONVERTER_OCR_")
                || key.equals("FORMAT_CONVERTER_APP_HOME") || key.equals("FORMAT_CONVERTER_TESSERACT_BINARY"));
        builder.environment().putAll(overrides);
        Process parent = builder.start();
        try {
            assertTrue(parent.waitFor(40, TimeUnit.SECONDS), "OCR parent/worker probe timed out");
            assertEquals(0, parent.exitValue(), Files.readString(log));
            if (expected == null) assertTrue(Files.notExists(output), "显式关闭后不能生成OCR产物");
            else assertEquals(expected, Files.readString(output).strip());
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }

    private void writeOcrBinary(Path binary, String value) throws Exception {
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 'tesseract 5.5.2-test'; exit 0; fi\n"
                + "if [ \"$1\" = \"--list-langs\" ]; then printf 'List of available languages:\\neng\\nchi_sim\\n'; exit 0; fi\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"$2.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t10\\t10\\t150\\t30\\t95\\t" + value + "\\n' >> \"$2.tsv\"\n");
        assertTrue(binary.toFile().setExecutable(true));
    }

    private byte[] solidImage(String format, int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static List<String> workerCommand(Class<?> mainClass) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return List.of(java.toString(), "-Xmx256m", "-cp", System.getProperty("java.class.path"), mainClass.getName());
    }

    private TaskSnapshot await(ConversionTaskService service, String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            TaskSnapshot current = service.get(taskId);
            if (current.status() == TaskStatus.SUCCESS || current.status() == TaskStatus.FAILED) return current;
            Thread.sleep(50);
        }
        fail("任务未在期限内结束");
        return null;
    }

    public static final class ExitWithoutResponseMain {
        public static void main(String[] args) { System.exit(17); }
    }

    public static final class SleepingMain {
        public static void main(String[] args) throws Exception { Thread.sleep(60_000); }
    }

    public static final class OcrParentMain {
        public static void main(String[] args) throws Exception {
            String discovery = args[0];
            Path home = Path.of(args[1]);
            System.clearProperty("format.converter.app.home");
            System.clearProperty("jpackage.app-path");
            System.setProperty(discovery, discovery.equals("jpackage.app-path")
                    ? home.resolve("FuyueConvert.exe").toString() : home.toString());
            boolean disabled = args[5].equals("DISABLED");
            var capability = TesseractOcrConverter.detectConfigured();
            if (capability.enabled() == disabled || !disabled && !capability.available()) {
                throw new AssertionError("Unexpected parent capability: " + capability);
            }
            var route = DefaultConverterRegistry.create(null, Duration.ofSeconds(10)).stream()
                    .filter(converter -> converter.route().sourceFormat() == DocumentFormat.PNG
                            && converter.route().targetFormat() == DocumentFormat.TXT)
                    .findFirst().orElseThrow().route();
            ForkedFileConverter converter = new ForkedFileConverter(route,
                    workerCommand(ConversionWorkerMain.class), "", Duration.ofSeconds(10));
            Path input = Path.of(args[2]);
            try {
                converter.convert(new ConversionInput("input.png", "image/png", Files.size(input), input),
                        Path.of(args[3]), Path.of(args[4]), ParseLimits.defaults(), (stage, progress) -> { });
                if (disabled) throw new AssertionError("Worker unexpectedly enabled explicitly disabled OCR");
            } catch (ConversionFailureException failure) {
                if (!disabled || !failure.code().equals("OCR_ENGINE_UNAVAILABLE")) throw failure;
            }
        }
    }
}
