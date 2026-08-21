package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real-engine gold cases. Tests skip locally when the requested engine/model/font is absent. */
class TesseractMultilingualIntegrationTest {
    @TempDir Path temp;

    @Test
    void recognizesChineseEnglishNumbersAndPunctuation() throws Exception {
        Path binary = requireTesseract("chi_sim", "eng");
        Font font = requireFont("中文测试，。2026");
        Path image = temp.resolve("chinese.png");
        writeHorizontalText(image, "中文测试 2026，。", font, 1500, 320);
        var converter = converter(DocumentFormat.PNG, binary, "chi_sim+eng");
        Path output = temp.resolve("chinese.txt");

        ConversionOutput converted = converter.convert(input(image, "image/png"), temp.resolve("chinese-work"),
                output, ParseLimits.defaults(), (stage, percent) -> { });

        String text = compact(Files.readString(output));
        assertTrue(text.contains("中文测试"), text);
        assertTrue(text.contains("2026"), text);
        assertTrue(containsPunctuation(text), text);
        assertTrue(converted.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_APPLIED
                && warning.confidence() != null));
    }

    @Test
    void recognizesVerticalChineseWithVerticalLanguageModel() throws Exception {
        Path binary = requireTesseract("chi_sim_vert");
        Font font = requireFont("天地玄黄");
        BufferedImage image = canvas(500, 1100);
        Graphics2D graphics = graphics(image, font.deriveFont(Font.BOLD, 110f));
        String value = "天地玄黄";
        for (int index = 0; index < value.length(); index++) {
            graphics.drawString(String.valueOf(value.charAt(index)), 185, 180 + index * 210);
        }
        graphics.dispose();
        Path source = temp.resolve("vertical.png");
        ImageIO.write(image, "png", source.toFile());
        Path output = temp.resolve("vertical.txt");

        converter(DocumentFormat.PNG, binary, "chi_sim_vert").convert(input(source, "image/png"),
                temp.resolve("vertical-work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        String recognized = compact(Files.readString(output));
        long common = value.chars().filter(character -> recognized.indexOf(character) >= 0).count();
        assertTrue(common >= 2, "expected vertical gold recall >= 50%, actual=" + recognized);
    }

    @Test
    void appliesExifRotationBeforeRunningRealOcr() throws Exception {
        Path binary = requireTesseract("eng");
        BufferedImage visible = canvas(1400, 320);
        Graphics2D graphics = graphics(visible, new Font(Font.SANS_SERIF, Font.BOLD, 94));
        graphics.drawString("ROTATED OCR 2026", 100, 205);
        graphics.dispose();
        BufferedImage stored = OcrImageNormalizer.orient(visible, 8);
        Path source = temp.resolve("rotated.jpg");
        writeJpegWithExifOrientation(stored, source, 6);
        Path output = temp.resolve("rotated.txt");

        ConversionOutput converted = converter(DocumentFormat.JPG, binary, "eng").convert(
                input(source, "image/jpeg"), temp.resolve("rotated-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        String recognized = Files.readString(output).toUpperCase(Locale.ROOT);
        assertTrue(recognized.contains("ROTATED OCR 2026"), recognized);
        assertTrue(converted.warnings().stream()
                .anyMatch(warning -> warning.code() == WarningCode.EXIF_ORIENTATION_APPLIED));
    }

    private TesseractOcrConverter converter(DocumentFormat format, Path binary, String languages) {
        var settings = new TesseractOcrConverter.Settings(binary, languages,
                TesseractOcrConverter.version(binary).orElse("unknown"), Duration.ofSeconds(30),
                1, 0d, 0d, 25_000_000L, temp.resolve("ocr-locks"));
        return new TesseractOcrConverter(format, settings);
    }

    private Path requireTesseract(String... languages) {
        var discovered = TesseractOcrConverter.discover("");
        assumeTrue(discovered.isPresent(), "Tesseract is not installed");
        var available = TesseractOcrConverter.languages(discovered.orElseThrow());
        assumeTrue(List.of(languages).stream().allMatch(available::contains),
                "Missing Tesseract language models: " + List.of(languages));
        return discovered.orElseThrow();
    }

    private Font requireFont(String text) {
        Font[] candidates = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();
        for (Font candidate : candidates) {
            Font font = candidate.deriveFont(Font.PLAIN, 100f);
            if (font.canDisplayUpTo(text) < 0) return font;
        }
        assumeTrue(false, "No installed font can render the CJK gold text");
        return new Font(Font.SANS_SERIF, Font.PLAIN, 100);
    }

    private void writeHorizontalText(Path output, String text, Font font, int width, int height) throws Exception {
        BufferedImage image = canvas(width, height);
        Graphics2D graphics = graphics(image, font.deriveFont(Font.BOLD, 105f));
        graphics.drawString(text, 70, 205);
        graphics.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private BufferedImage canvas(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private Graphics2D graphics(BufferedImage image, Font font) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.setFont(font);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return graphics;
    }

    private void writeJpegWithExifOrientation(BufferedImage image, Path output, int orientation) throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", encoded);
        byte[] jpeg = encoded.toByteArray();
        byte[] exif = new byte[]{
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0,
                1, 0,
                0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
                0, 0, 0, 0
        };
        int segmentLength = exif.length + 2;
        ByteArrayOutputStream result = new ByteArrayOutputStream(jpeg.length + exif.length + 4);
        result.write(jpeg, 0, 2);
        result.write(0xff);
        result.write(0xe1);
        result.write((segmentLength >>> 8) & 0xff);
        result.write(segmentLength & 0xff);
        result.write(exif);
        result.write(jpeg, 2, jpeg.length - 2);
        Files.write(output, result.toByteArray());
    }

    private boolean containsPunctuation(String value) {
        return value.contains("，") || value.contains("。") || value.contains(",") || value.contains(".");
    }

    private String compact(String value) { return value.replaceAll("\\s+", ""); }

    private ConversionInput input(Path source, String contentType) throws Exception {
        return new ConversionInput(source.getFileName().toString(), contentType, Files.size(source), source);
    }
}
