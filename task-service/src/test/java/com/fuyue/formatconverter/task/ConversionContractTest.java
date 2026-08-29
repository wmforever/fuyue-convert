package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ConversionContractTest {
    @TempDir Path temp;

    @Test
    void reportsPartialSuccessForOrdinaryBatchZip() throws Exception {
        FileConverter converter = new FileConverter() {
            @Override public ConversionRoute route() {
                return ConversionRoute.of(DocumentFormat.TXT, DocumentFormat.DOCX, "partial batch test");
            }

            @Override
            public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                            ParseLimits limits, ConversionProgress progress) throws Exception {
                if (input.displayName().startsWith("bad")) {
                    throw new ConversionFailureException("INVALID_TEST_INPUT", "测试文件无法转换");
                }
                Files.writeString(outputPath, "converted", StandardCharsets.UTF_8);
                return new ConversionOutput(outputPath,
                        input.displayName().replaceFirst("(?i)\\.txt$", ".docx"), null, List.of());
            }
        };
        byte[] good = "good".getBytes(StandardCharsets.UTF_8);
        byte[] bad = "bad".getBytes(StandardCharsets.UTF_8);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("partial-batch"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, List.of(converter))) {
            TaskSnapshot created = service.createTask(List.of(
                    upload("good.txt", good), upload("bad.txt", bad)), DocumentFormat.DOCX);
            TaskSnapshot finished = await(service, created.taskId());

            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertEquals(1, finished.files().stream().filter(TaskFileResult::success).count());
            assertEquals(1, finished.files().stream().filter(result -> !result.success()).count());
            assertTrue(finished.warnings().stream()
                    .anyMatch(warning -> warning.code() == WarningCode.PARTIAL_BATCH_OUTPUT
                            && warning.message().contains("1 / 2")
                            && warning.message().contains("conversion-report.txt")));
            try (ZipFile zip = new ZipFile(service.download(created.taskId()).path().toFile())) {
                assertTrue(zip.getEntry("good.docx") != null);
                assertTrue(zip.getEntry("conversion-report.txt") != null);
                assertFalse(zip.stream().anyMatch(entry -> entry.getName().equals("bad.docx")));
            }
        }
    }

    @Test
    void advertisesSingleFileLimitForPdfWatermarkAndCompression() {
        ConversionRoute watermark = new PdfWatermarkConverter().route();
        ConversionRoute compression = new PdfCompressConverter().route();

        assertTrue(watermark.limitations().stream().anyMatch(limit -> limit.contains("一次仅支持处理一个")));
        assertTrue(compression.limitations().stream().anyMatch(limit -> limit.contains("一次仅支持处理一个")));
    }

    @Test
    void exposesMissingLibreOfficeAsActionableUnavailablePptxRoute() throws Exception {
        List<FileConverter> converters = DefaultConverterRegistry.create(null, Duration.ofSeconds(30));
        ConversionRoute route = converters.stream().map(FileConverter::route)
                .filter(candidate -> candidate.id().equals("pptx-to-pdf"))
                .findFirst().orElseThrow();

        assertEquals(RouteStatus.UNAVAILABLE, route.status());
        assertEquals(QualityLevel.BETA, route.qualityLevel());
        assertTrue(route.requires().contains("libreoffice"));
        assertTrue(route.limitations().stream().anyMatch(limit -> limit.contains("安装")
                && limit.contains("FORMAT_CONVERTER_OFFICE_BINARY")));

        byte[] pptx = emptyPptx();
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("missing-office"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1), ParseLimits.defaults());
        try (ConversionTaskService service = new ConversionTaskService(config, converters)) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("slides.pptx",
                    DocumentFormat.PPTX.contentType(), pptx.length, () -> new ByteArrayInputStream(pptx))),
                    DocumentFormat.PDF);
            TaskSnapshot failed = await(service, created.taskId());

            assertEquals(TaskStatus.FAILED, failed.status());
            assertEquals("OFFICE_ENGINE_UNAVAILABLE", failed.errorCode());
            assertEquals("OFFICE_ENGINE_UNAVAILABLE", failed.files().get(0).errorCode());
            assertTrue(failed.errorMessage().contains("安装"));
            assertTrue(failed.errorMessage().contains("FORMAT_CONVERTER_OFFICE_BINARY"));
        }
    }

    private UploadPayload upload(String name, byte[] data) {
        return new UploadPayload(name, "text/plain", data.length, () -> new ByteArrayInputStream(data));
    }

    private byte[] emptyPptx() throws Exception {
        try (XMLSlideShow slides = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slides.createSlide();
            slides.write(output);
            return output.toByteArray();
        }
    }

    private TaskSnapshot await(ConversionTaskService service, String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            TaskSnapshot snapshot = service.get(taskId);
            if (snapshot.status() == TaskStatus.SUCCESS || snapshot.status() == TaskStatus.FAILED
                    || snapshot.status() == TaskStatus.CANCELLED) return snapshot;
            Thread.sleep(20);
        }
        fail("task did not finish");
        return null;
    }
}
