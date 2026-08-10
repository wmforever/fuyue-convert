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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BundledOcrRuntimeIntegrationTest {
    @TempDir Path temp;

    @Test
    void bundledBinaryModelsAndLibrariesRunWithoutSystemDiscovery() throws Exception {
        var capability = TesseractOcrConverter.detectConfigured();
        assumeTrue(capability.available() && capability.settings().bundled(),
                "Run with FORMAT_CONVERTER_APP_HOME pointing to a prepared app/ocr runtime");
        Path image = textImage();
        Path output = temp.resolve("bundled.txt");

        ConversionOutput converted = new TesseractOcrConverter(DocumentFormat.PNG, capability.settings())
                .convert(new ConversionInput("bundled.png", "image/png", Files.size(image), image),
                        temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        assertTrue(Files.readString(output).toUpperCase(Locale.ROOT).contains("BUNDLED OCR 2026"));
        assertTrue(converted.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_APPLIED));
    }

    private Path textImage() throws Exception {
        BufferedImage image = new BufferedImage(1200, 280, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 84));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("BUNDLED OCR 2026", 70, 185);
        graphics.dispose();
        Path source = temp.resolve("bundled.png");
        ImageIO.write(image, "png", source.toFile());
        return source;
    }
}
