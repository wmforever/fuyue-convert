package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TesseractOcrConverterTest {
    @TempDir Path temp;

    @Test
    void extractsRealTextAndReportsOcrWarning() throws Exception {
        var discovered = TesseractOcrConverter.discover("");
        assumeTrue(discovered.isPresent(), "Tesseract is not installed");
        assumeTrue(TesseractOcrConverter.languages(discovered.orElseThrow()).contains("eng"),
                "Tesseract English model is not installed");
        Path source = createTextImage();
        Path output = temp.resolve("ocr.txt");
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, discovered.orElseThrow(), "eng",
                Duration.ofSeconds(30));

        ConversionOutput converted = converter.convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        String text = Files.readString(output).toUpperCase(Locale.ROOT);
        assertTrue(text.contains("OCR TEST 2026"), text);
        assertEquals(1, converted.pageCount());
        assertTrue(converted.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_APPLIED));
        assertEquals(QualityLevel.EXPERIMENTAL, converter.route().qualityLevel());
        assertEquals(ConversionStrategy.EXTRACTION, converter.route().strategy());
    }

    @Test
    void rejectsUnsafeLanguageArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TesseractOcrConverter(DocumentFormat.PNG,
                Path.of("tesseract"), "eng;touch_bad", Duration.ofSeconds(10)));
    }

    private Path createTextImage() throws Exception {
        BufferedImage image = new BufferedImage(1000, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("OCR TEST 2026", 80, 150);
        graphics.dispose();
        Path source = temp.resolve("ocr-source.png");
        ImageIO.write(image, "png", source.toFile());
        return source;
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "image/png", Files.size(source), source);
    }
}
