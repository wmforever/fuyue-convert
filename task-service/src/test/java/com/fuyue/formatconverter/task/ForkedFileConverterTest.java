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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

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

    private List<String> workerCommand(Class<?> mainClass) {
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
}
