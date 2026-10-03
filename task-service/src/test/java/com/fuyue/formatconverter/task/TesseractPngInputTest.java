package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TesseractPngInputTest {
    @TempDir Path temp;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    @Test
    void sendsJpegAsPngWithoutChangingPixelsDimensionsSourceOrPageCoordinates() throws Exception {
        Path source = image("jpeg", "source.jpg");
        byte[] original = Files.readAllBytes(source);
        Path work = temp.resolve("work");
        var result = converter().recognizeLayoutResult(source, work, 7,
                new Rect(10, 20, 240, 160), ParseLimits.defaults());

        Path received = work.resolve("tesseract-page-0007.received");
        assertArrayEquals(PNG, Arrays.copyOf(Files.readAllBytes(received), PNG.length));
        BufferedImage expected = ImageIO.read(source.toFile());
        BufferedImage actual = ImageIO.read(received.toFile());
        try {
            assertEquals(120, actual.getWidth());
            assertEquals(80, actual.getHeight());
            assertArrayEquals(expected.getRGB(0, 0, 120, 80, null, 0, 120),
                    actual.getRGB(0, 0, 120, 80, null, 0, 120), "PNG 转换不得重新压缩或改变解码后的 JPEG 像素");
        } finally {
            expected.flush(); actual.flush();
        }
        assertArrayEquals(original, Files.readAllBytes(source));
        Path engineInput = Path.of(Files.readString(work.resolve("tesseract-page-0007.input-path")).strip());
        assertEquals(work, engineInput.getParent());
        assertNotEquals(source, engineInput);
        assertTrue(Files.notExists(engineInput), "识别完成后释放派生 PNG 文件");
        var block = result.blocks().get(0);
        assertEquals(7, block.pageNumber());
        assertEquals(new Rect(30, 30, 80, 40), block.box());
        assertEquals("PNG INPUT", block.text());
    }

    @Test
    void reusesPngBySignatureEvenWhenFilenameHasADifferentExtension() throws Exception {
        Path source = image("png", "already-normalized.jpg");
        byte[] original = Files.readAllBytes(source);
        Path work = temp.resolve("png-work");
        converter().recognizeLayoutResult(source, work, 2,
                new Rect(0, 0, 120, 80), ParseLimits.defaults());

        assertEquals(source.toString(), Files.readString(work.resolve("tesseract-page-0002.input-path")).strip());
        assertArrayEquals(original, Files.readAllBytes(work.resolve("tesseract-page-0002.received")));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(Files.exists(source), "原始 PNG 必须保留且不得重新编码");
    }

    @Test
    void bundledPngEngineRecognizesRealJpegText() throws Exception {
        var capability = TesseractOcrConverter.detectConfigured();
        assumeTrue(capability.available() && capability.settings().bundled()
                        && capability.availableLanguages().contains("eng"),
                "Run with FORMAT_CONVERTER_APP_HOME pointing to a prepared app/ocr runtime");
        BufferedImage image = new BufferedImage(1200, 280, BufferedImage.TYPE_INT_RGB);
        Path source = temp.resolve("jpeg-text.jpg");
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1200, 280);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 84));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString("JPEG OCR 2026", 70, 185);
            assertTrue(ImageIO.write(image, "jpeg", source.toFile()));
        } finally {
            graphics.dispose(); image.flush();
        }
        byte[] original = Files.readAllBytes(source);
        Path output = temp.resolve("jpeg-text.txt");
        new TesseractOcrConverter(DocumentFormat.JPG, capability.settings()).convert(
                new ConversionInput("jpeg-text.jpg", "image/jpeg", original.length, source),
                temp.resolve("bundled-work"), output, ParseLimits.defaults(), (stage, percent) -> { });
        assertTrue(Files.readString(output).toUpperCase(Locale.ROOT).contains("JPEG OCR 2026"));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    private TesseractOcrConverter converter() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path binary = temp.resolve("fake-tesseract");
        Files.writeString(binary, """
                #!/bin/sh
                cp "$1" "${2}.received"
                printf '%s\\n' "$1" > "${2}.input-path"
                printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > "${2}.tsv"
                printf '5\\t1\\t1\\t1\\t1\\t1\\t10\\t5\\t40\\t20\\t95\\tPNG INPUT\\n' >> "${2}.tsv"
                """);
        assertTrue(binary.toFile().setExecutable(true));
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.2, 0.8, 25_000_000L, temp.resolve("locks"));
        return new TesseractOcrConverter(DocumentFormat.JPG, settings);
    }

    private Path image(String format, String filename) throws Exception {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, ((x * 2) << 16) | ((y * 3) << 8) | ((x + y) & 0xff));
                }
            }
            Path source = temp.resolve(filename);
            assertTrue(ImageIO.write(image, format, source.toFile()));
            return source;
        } finally {
            image.flush();
        }
    }
}
