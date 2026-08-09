package com.fuyue.formatconverter.task;

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
