package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ImageToPdfConverterTest {
    @TempDir Path temp;

    @Test
    void usesPngPhysicalDpiAndPreservesTransparency() throws Exception {
        BufferedImage image = new BufferedImage(300, 150, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(java.awt.AlphaComposite.Clear);
        graphics.fillRect(0, 0, 300, 150);
        graphics.setComposite(java.awt.AlphaComposite.Src);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(100, 40, 100, 70);
        graphics.dispose();
        Path source = temp.resolve("physical.png");
        Files.write(source, pngWithDpi(image, 150));
        Path output = temp.resolve("physical.pdf");

        ConversionOutput result = new ImageToPdfConverter(DocumentFormat.PNG).convert(input(source, "image/png"),
                temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        assertFalse(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.IMAGE_DPI_DEFAULTED));
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(144d, pdf.getPage(0).getMediaBox().getWidth(), 0.1d);
            assertEquals(72d, pdf.getPage(0).getMediaBox().getHeight(), 0.1d);
            BufferedImage rendered = new PDFRenderer(pdf).renderImageWithDPI(0, 150);
            assertEquals(300, rendered.getWidth(), 1);
            assertEquals(150, rendered.getHeight(), 1);
            assertColorNear(Color.WHITE, new Color(rendered.getRGB(10, 10)), 8);
            assertColorNear(Color.GREEN, new Color(rendered.getRGB(150, 75)), 8);
        }
    }

    @Test
    void appliesExifOrientationAndExifResolutionToJpeg() throws Exception {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 20, 20);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(20, 0, 20, 20);
        graphics.dispose();
        Path source = temp.resolve("oriented.jpg");
        Files.write(source, jpegWithExif(image, 6, 100));
        Path output = temp.resolve("oriented.pdf");

        ConversionOutput result = new ImageToPdfConverter(DocumentFormat.JPG).convert(input(source, "image/jpeg"),
                temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.EXIF_ORIENTATION_APPLIED));
        assertFalse(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.IMAGE_DPI_DEFAULTED));
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(14.4d, pdf.getPage(0).getMediaBox().getWidth(), 0.05d);
            assertEquals(28.8d, pdf.getPage(0).getMediaBox().getHeight(), 0.05d);
            BufferedImage rendered = new PDFRenderer(pdf).renderImageWithDPI(0, 100);
            assertColorNear(Color.RED, new Color(rendered.getRGB(10, 5)), 45);
            assertColorNear(Color.BLUE, new Color(rendered.getRGB(10, 35)), 45);
        }
    }

    @Test
    void mergesMultipleImagesIntoOnePdfInUploadOrder() throws Exception {
        byte[] first = pngWithDpi(solidImage(100, 50, Color.RED), 100);
        byte[] second = pngWithDpi(solidImage(50, 100, Color.BLUE), 100);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("batch"), 1, 4,
                Duration.ofSeconds(20), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(),
                new OfdrwParser(), new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(
                    new UploadPayload("first.png", "image/png", first.length, () -> new ByteArrayInputStream(first)),
                    new UploadPayload("second.png", "image/png", second.length, () -> new ByteArrayInputStream(second))),
                    DocumentFormat.PDF);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertEquals("merged-images.pdf", finished.downloadName());
            try (var pdf = Loader.loadPDF(service.download(created.taskId()).path().toFile())) {
                assertEquals(2, pdf.getNumberOfPages());
                assertEquals(72d, pdf.getPage(0).getMediaBox().getWidth(), 0.1d);
                assertEquals(36d, pdf.getPage(0).getMediaBox().getHeight(), 0.1d);
                assertEquals(36d, pdf.getPage(1).getMediaBox().getWidth(), 0.1d);
                assertEquals(72d, pdf.getPage(1).getMediaBox().getHeight(), 0.1d);
            }
        }
    }

    @Test
    void fitsRotatedJpegIntoPortraitA4WithoutCroppingOrStretching() throws Exception {
        BufferedImage image = solidImage(200, 100, Color.BLUE);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 100, 100);
        graphics.dispose();
        Path source = temp.resolve("a4-rotated.jpg");
        Files.write(source, jpegWithExif(image, 6, 100));
        ConversionOptions options = ConversionOptions.fromRequest(null, null, null, null, null,
                null, null, null, null, null, null, "a4-portrait", 20d);
        Path output = temp.resolve("a4-rotated.pdf");
        new ImageToPdfConverter(DocumentFormat.JPG).convert(new ConversionInput("photo.jpg", "image/jpeg",
                Files.size(source), source, options), temp.resolve("a4-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });
        try (var pdf = Loader.loadPDF(output.toFile())) {
            var page = pdf.getPage(0).getMediaBox();
            assertEquals(210d, page.getWidth() * 25.4 / 72, 0.1d);
            assertEquals(297d, page.getHeight() * 25.4 / 72, 0.1d);
            BufferedImage rendered = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            // A rotated 1:2 image fills available height, retains all four edges and is centered.
            int cx = rendered.getWidth() / 2;
            int top = Math.round(20f * 72f / 25.4f);
            int imageHeight = rendered.getHeight() - 2 * top;
            int left = (rendered.getWidth() - imageHeight / 2) / 2;
            assertColorNear(Color.WHITE, new Color(rendered.getRGB(cx, top - 5)), 8);
            assertColorNear(Color.WHITE, new Color(rendered.getRGB(left - 5, rendered.getHeight() / 2)), 8);
            assertColorNear(Color.RED, new Color(rendered.getRGB(left + 5, top + 5)), 45);
            assertColorNear(Color.BLUE, new Color(rendered.getRGB(rendered.getWidth() - left - 6,
                    rendered.getHeight() - top - 6)), 45);
        }
    }

    @Test
    void mixedImageBatchUsesA4AutoAfterExifAndKeepsOrder() throws Exception {
        byte[] png = pngWithDpi(solidImage(100, 50, Color.RED), 100);
        byte[] jpeg = jpegWithExif(solidImage(40, 20, Color.BLUE), 6, 100);
        ConversionOptions options = ConversionOptions.fromRequest(null, null, null, null, null,
                null, null, null, null, null, null, "a4-auto", 10d);
        try (ConversionTaskService service = new ConversionTaskService(batchConfig("a4-mixed"), imageConverters())) {
            TaskSnapshot task = service.createTask(List.of(upload("wide.png", "image/png", png),
                    upload("rotated.jpg", "image/jpeg", jpeg)), DocumentFormat.PDF, options);
            assertEquals(TaskStatus.SUCCESS, await(service, task.taskId()).status());
            try (var pdf = Loader.loadPDF(service.download(task.taskId()).path().toFile())) {
                assertEquals(2, pdf.getNumberOfPages());
                assertEquals(297d, pdf.getPage(0).getMediaBox().getWidth() * 25.4 / 72, 0.1d);
                assertEquals(210d, pdf.getPage(1).getMediaBox().getWidth() * 25.4 / 72, 0.1d);
                PDFRenderer renderer = new PDFRenderer(pdf);
                for (int i = 0; i < 2; i++) {
                    BufferedImage rendered = renderer.renderImage(i);
                    assertColorNear(i == 0 ? Color.RED : Color.BLUE,
                            new Color(rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2)), 20);
                }
            }
        }
    }

    @Test
    void mergesMixedPngAndJpegInEitherOrderUsingEachImagesDpiAndOrientation() throws Exception {
        byte[] png = pngWithDpi(solidImage(100, 50, Color.RED), 100);
        byte[] jpeg = jpegWithExif(solidImage(40, 20, Color.BLUE), 6, 100);
        UploadPayload pngUpload = upload("first.png", "image/png", png);
        UploadPayload jpegUpload = upload("second.JPEG", "image/jpeg", jpeg);
        for (boolean jpegFirst : List.of(false, true)) {
            TaskServiceConfig config = batchConfig("mixed-" + jpegFirst);
            try (ConversionTaskService service = new ConversionTaskService(config, imageConverters())) {
                TaskSnapshot created = service.createTask(jpegFirst
                        ? List.of(jpegUpload, pngUpload) : List.of(pngUpload, jpegUpload), DocumentFormat.PDF);
                TaskSnapshot finished = await(service, created.taskId());
                assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
                assertEquals(jpegFirst ? DocumentFormat.JPG : DocumentFormat.PNG, finished.sourceFormat());
                assertEquals(jpegFirst ? List.of(DocumentFormat.JPG, DocumentFormat.PNG)
                                : List.of(DocumentFormat.PNG, DocumentFormat.JPG),
                        finished.files().stream().map(TaskFileResult::sourceFormat).toList());
                assertFalse(finished.warnings().stream().anyMatch(w -> w.code() == WarningCode.IMAGE_DPI_DEFAULTED));
                assertTrue(finished.warnings().stream().anyMatch(w -> w.code() == WarningCode.EXIF_ORIENTATION_APPLIED));
                try (var pdf = Loader.loadPDF(service.download(created.taskId()).path().toFile())) {
                    assertEquals(2, pdf.getNumberOfPages());
                    int pngPage = jpegFirst ? 1 : 0;
                    int jpegPage = 1 - pngPage;
                    assertEquals(72d, pdf.getPage(pngPage).getMediaBox().getWidth(), 0.1d);
                    assertEquals(36d, pdf.getPage(pngPage).getMediaBox().getHeight(), 0.1d);
                    assertEquals(14.4d, pdf.getPage(jpegPage).getMediaBox().getWidth(), 0.1d);
                    assertEquals(28.8d, pdf.getPage(jpegPage).getMediaBox().getHeight(), 0.1d);
                    PDFRenderer renderer = new PDFRenderer(pdf);
                    assertColorNear(Color.RED, new Color(renderer.renderImage(pngPage).getRGB(5, 5)), 8);
                    assertColorNear(Color.BLUE, new Color(renderer.renderImage(jpegPage).getRGB(5, 5)), 15);
                }
            }
        }
    }

    @Test
    void validatesEachMixedImagesMimeAndHeaderAndRejectsUnsupportedExtensions() throws Exception {
        byte[] png = pngWithDpi(solidImage(100, 50, Color.RED), 100);
        byte[] jpeg = jpegWithExif(solidImage(40, 20, Color.BLUE), 1, 100);
        try (ConversionTaskService service = new ConversionTaskService(batchConfig("invalid-mixed"), imageConverters())) {
            UploadPayload first = upload("valid.png", "image/png", png);
            IllegalArgumentException mime = assertThrows(IllegalArgumentException.class, () -> service.createTask(
                    List.of(first, upload("wrong.jpg", "image/png", jpeg)), DocumentFormat.PDF));
            assertTrue(mime.getMessage().contains("MIME"), mime.getMessage());
            IllegalArgumentException jpegHeader = assertThrows(IllegalArgumentException.class, () -> service.createTask(
                    List.of(first, upload("wrong.jpg", "image/jpeg", png)), DocumentFormat.PDF));
            assertTrue(jpegHeader.getMessage().contains("JPEG 图片 文件头校验失败"), jpegHeader.getMessage());
            IllegalArgumentException pngHeader = assertThrows(IllegalArgumentException.class, () -> service.createTask(
                    List.of(upload("valid.jpg", "image/jpeg", jpeg), upload("wrong.png", "image/png", jpeg)),
                    DocumentFormat.PDF));
            assertTrue(pngHeader.getMessage().contains("PNG 图片 文件头校验失败"), pngHeader.getMessage());
            assertThrows(IllegalArgumentException.class, () -> service.createTask(
                    List.of(first, upload("unsupported.gif", "image/gif", png)), DocumentFormat.PDF));
            assertTrue(service.listTasks(10).isEmpty());
        }
    }

    @Test
    void continuesToRejectMixedSourcesForOtherConversions() throws Exception {
        byte[] png = pngWithDpi(solidImage(100, 50, Color.RED), 100);
        byte[] jpeg = jpegWithExif(solidImage(40, 20, Color.BLUE), 1, 100);
        List<FileConverter> routes = List.of(failingConverter(DocumentFormat.PNG, DocumentFormat.TXT),
                failingConverter(DocumentFormat.JPG, DocumentFormat.TXT),
                failingConverter(DocumentFormat.TXT, DocumentFormat.PDF), new ImageToPdfConverter(DocumentFormat.PNG));
        try (ConversionTaskService service = new ConversionTaskService(batchConfig("other-mixed"), routes)) {
            IllegalArgumentException ocr = assertThrows(IllegalArgumentException.class, () -> service.createTask(
                    List.of(upload("first.png", "image/png", png), upload("second.jpg", "image/jpeg", jpeg)),
                    DocumentFormat.TXT));
            assertTrue(ocr.getMessage().contains("相同源格式"));
            IllegalArgumentException documents = assertThrows(IllegalArgumentException.class, () -> service.createTask(
                    List.of(upload("first.png", "image/png", png), upload("second.txt", "text/plain", new byte[]{65})),
                    DocumentFormat.PDF));
            assertTrue(documents.getMessage().contains("相同源格式"));
        }
    }

    @Test
    void recoversAndRetriesEachMixedInputFormatEvenWhenNoFileResultsWerePersisted() throws Exception {
        byte[] png = pngWithDpi(solidImage(100, 50, Color.RED), 100);
        byte[] jpeg = jpegWithExif(solidImage(40, 20, Color.BLUE), 6, 100);
        for (boolean unfinished : List.of(false, true)) {
            TaskServiceConfig config = batchConfig("retry-mixed-" + unfinished);
            String taskId;
            try (ConversionTaskService service = new ConversionTaskService(config, List.of(
                    failingConverter(DocumentFormat.PNG, DocumentFormat.PDF),
                    failingConverter(DocumentFormat.JPG, DocumentFormat.PDF)))) {
                taskId = service.createTask(List.of(upload("first.png", "image/png", png),
                        upload("second.jpeg", "image/jpeg", jpeg)), DocumentFormat.PDF).taskId();
                TaskSnapshot failed = await(service, taskId);
                assertEquals(TaskStatus.FAILED, failed.status());
                assertEquals(List.of(DocumentFormat.PNG, DocumentFormat.JPG),
                        failed.files().stream().map(TaskFileResult::sourceFormat).toList());
            }
            if (unfinished) {
                Path manifest = config.dataRoot().resolve("tasks").resolve(taskId).resolve("manifest.json");
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode snapshot = (ObjectNode) mapper.readTree(manifest.toFile());
                snapshot.put("status", "WAITING");
                snapshot.putArray("files");
                mapper.writeValue(manifest.toFile(), snapshot);
            }
            try (ConversionTaskService recovered = new ConversionTaskService(config, imageConverters())) {
                assertEquals(TaskStatus.FAILED, recovered.get(taskId).status());
                if (unfinished) assertEquals("SERVICE_RESTARTED", recovered.get(taskId).errorCode());
                TaskSnapshot retried = await(recovered, recovered.retry(taskId).taskId());
                assertEquals(TaskStatus.SUCCESS, retried.status(), retried.errorMessage());
                assertEquals(List.of(DocumentFormat.PNG, DocumentFormat.JPG),
                        retried.files().stream().map(TaskFileResult::sourceFormat).toList());
                assertEquals(unfinished ? List.of("document-1.png", "document-2.jpg")
                                : List.of("first.png", "second.jpeg"),
                        retried.files().stream().map(TaskFileResult::fileName).toList());
                try (var pdf = Loader.loadPDF(recovered.download(retried.taskId()).path().toFile())) {
                    assertEquals(2, pdf.getNumberOfPages());
                    assertEquals(72d, pdf.getPage(0).getMediaBox().getWidth(), 0.1d);
                    assertEquals(14.4d, pdf.getPage(1).getMediaBox().getWidth(), 0.1d);
                }
            }
        }
    }

    private List<FileConverter> imageConverters() {
        return List.of(new ImageToPdfConverter(DocumentFormat.PNG), new ImageToPdfConverter(DocumentFormat.JPG));
    }

    private TaskServiceConfig batchConfig(String directory) {
        return new TaskServiceConfig(temp.resolve(directory), 1, 4,
                Duration.ofSeconds(20), Duration.ofHours(1), ParseLimits.defaults());
    }

    private UploadPayload upload(String name, String mime, byte[] bytes) {
        return new UploadPayload(name, mime, bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    private FileConverter failingConverter(DocumentFormat source, DocumentFormat target) {
        return new FileConverter() {
            @Override public ConversionRoute route() { return ConversionRoute.of(source, target, "test route"); }
            @Override public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                                       ParseLimits limits, ConversionProgress progress) throws Exception {
                throw new ConversionFailureException("TEST_UNAVAILABLE", "暂时不可用，供重启后重试");
            }
        };
    }

    private byte[] pngWithDpi(BufferedImage image, int dpi) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ImageIO.write(image, "png", raw);
        byte[] png = raw.toByteArray();
        int pixelsPerMeter = (int) Math.round(dpi / 0.0254d);
        ByteBuffer data = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
                .putInt(pixelsPerMeter).putInt(pixelsPerMeter).put((byte) 1);
        byte[] type = new byte[]{'p', 'H', 'Y', 's'};
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data.array());
        ByteBuffer chunk = ByteBuffer.allocate(4 + 4 + 9 + 4).order(ByteOrder.BIG_ENDIAN)
                .putInt(9).put(type).put(data.array()).putInt((int) crc.getValue());
        byte[] result = new byte[png.length + chunk.array().length];
        System.arraycopy(png, 0, result, 0, 33);
        System.arraycopy(chunk.array(), 0, result, 33, chunk.array().length);
        System.arraycopy(png, 33, result, 33 + chunk.array().length, png.length - 33);
        return result;
    }

    private byte[] jpegWithExif(BufferedImage image, int orientation, int dpi) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", raw);
        byte[] jpeg = raw.toByteArray();
        ByteBuffer tiff = ByteBuffer.allocate(78).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 4);
        putShortEntry(tiff, 0x0112, orientation);
        putRationalEntry(tiff, 0x011a, 62);
        putRationalEntry(tiff, 0x011b, 70);
        putShortEntry(tiff, 0x0128, 2);
        tiff.putInt(0);
        tiff.putInt(dpi).putInt(1).putInt(dpi).putInt(1);
        byte[] exif = new byte[6 + tiff.array().length];
        System.arraycopy(new byte[]{'E', 'x', 'i', 'f', 0, 0}, 0, exif, 0, 6);
        System.arraycopy(tiff.array(), 0, exif, 6, tiff.array().length);
        ByteBuffer segment = ByteBuffer.allocate(4 + exif.length).order(ByteOrder.BIG_ENDIAN)
                .put((byte) 0xff).put((byte) 0xe1).putShort((short) (exif.length + 2)).put(exif);
        byte[] result = new byte[jpeg.length + segment.array().length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(segment.array(), 0, result, 2, segment.array().length);
        System.arraycopy(jpeg, 2, result, 2 + segment.array().length, jpeg.length - 2);
        return result;
    }

    private void putShortEntry(ByteBuffer buffer, int tag, int value) {
        buffer.putShort((short) tag).putShort((short) 3).putInt(1).putShort((short) value).putShort((short) 0);
    }

    private void putRationalEntry(ByteBuffer buffer, int tag, int offset) {
        buffer.putShort((short) tag).putShort((short) 5).putInt(1).putInt(offset);
    }

    private BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private void assertColorNear(Color expected, Color actual, int tolerance) {
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance
                        && Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance
                        && Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance,
                "expected=" + expected + ", actual=" + actual);
    }

    private ConversionInput input(Path source, String contentType) throws Exception {
        return new ConversionInput(source.getFileName().toString(), contentType, Files.size(source), source);
    }

    private TaskSnapshot await(ConversionTaskService service, String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            TaskSnapshot current = service.get(taskId);
            if (current.status() == TaskStatus.SUCCESS || current.status() == TaskStatus.FAILED) return current;
            Thread.sleep(50);
        }
        fail("任务未在期限内结束");
        return null;
    }
}
