package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the Windows launcher with the same absent console as the Electron backend. */
class WindowsOfficeLaunchProbeTest {
    @TempDir Path temp;

    @Test void convertsFromJavawWithoutAnAttachedConsole() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        String runtime = System.getenv("FORMAT_CONVERTER_WINDOWS_OFFICE_HOME");
        assumeTrue(runtime != null && !runtime.isBlank(), "Pinned Windows Office runtime is required");
        Path report = temp.resolve("report.json");
        Path stdout = temp.resolve("javaw.stdout.log"), stderr = temp.resolve("javaw.stderr.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString(),
                "-Djava.awt.headless=true", "-cp", classpath, NoConsoleConversion.class.getName(), runtime,
                temp.toString()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "javaw conversion probe timed out");
            assertEquals(0, process.exitValue(), () -> Files.exists(stderr) ? read(stderr) : "javaw failed");
            assertTrue(Files.isRegularFile(report), () -> read(stderr));
            String content = Files.readString(report);
            System.out.println("Windows no-console Office report: " + content);
            var results = new ObjectMapper().readTree(content);
            assertTrue(results.path("poi-soffice.com").path("success").asBoolean(), content);
            assertTrue(results.path("release-soffice.com").path("success").asBoolean(), content);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return error.toString(); }
    }

    public static class NoConsoleConversion {
        public static void main(String[] args) throws Exception {
            Path runtime = Path.of(args[0]), root = Path.of(args[1]);
            Path input = root.resolve("smoke.docx");
            try (var document = new XWPFDocument(); var output = Files.newOutputStream(input)) {
                document.createParagraph().createRun().setText("Fuyue Windows Full conversion 12345");
                document.write(output);
            }
            Map<String, Object> reports = new LinkedHashMap<>();
            for (String sample : new String[]{"poi", "release"}) {
            Path source = sample.equals("poi") ? input : Path.of(System.getenv("FORMAT_CONVERTER_WINDOWS_SMOKE_DOCX"));
            for (String launcher : new String[]{"soffice.exe", "soffice.com"}) {
                Path output = root.resolve(sample + "-" + launcher + ".pdf"),
                        work = root.resolve("工作目录 with spaces").resolve(sample + "-" + launcher + "-work");
                long started = System.nanoTime();
                Map<String, Object> result = new LinkedHashMap<>();
                try {
                    Path binary = runtime.resolve("program").resolve(launcher);
                    var route = new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF,
                            binary, Duration.ofSeconds(30), "no-console probe").route();
                    String classpath = System.getProperty("java.class.path");
                    new ForkedFileConverter(route, List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                            "-Djava.awt.headless=true", "-cp", classpath, ConversionWorkerMain.class.getName()),
                            binary.toString(), Duration.ofSeconds(30))
                            .convert(new ConversionInput("smoke.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    Files.size(source), source), work, output, ParseLimits.defaults(), (stage, percent) -> {});
                    try (var pdf = Loader.loadPDF(output.toFile())) {
                        String text = new PDFTextStripper().getText(pdf);
                        String expected = sample.equals("poi") ? "conversion 12345" : "Full edition smoke test";
                        result.put("success", pdf.getNumberOfPages() == 1 && text.contains(expected));
                        result.put("text", text.strip());
                    }
                } catch (Exception error) {
                    result.put("success", false);
                    result.put("error", error.toString());
                }
                result.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                reports.put(sample + "-" + launcher, result);
            }
            }
            new ObjectMapper().writeValue(root.resolve("report.json").toFile(), reports);
        }
    }
}
