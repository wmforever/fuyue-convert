package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void returnsPageConfidenceAndKeepsChineseNumbersAndPunctuation() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path source = createBlankImage();
        Path binary = fakeTesseract("88.0", "中文，OCR 2026！", false);
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.90d);
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        Path output = temp.resolve("confidence.txt");

        ConversionOutput converted = converter.convert(input(source), temp.resolve("confidence-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertTrue(Files.readString(output).contains("中文，OCR 2026！"));
        var applied = converted.warnings().stream()
                .filter(warning -> warning.code() == WarningCode.OCR_APPLIED).findFirst().orElseThrow();
        assertNotNull(applied.confidence());
        assertEquals(0.88d, applied.confidence(), 0.0001d);
        assertTrue(converted.warnings().stream()
                .anyMatch(warning -> warning.code() == WarningCode.OCR_LOW_CONFIDENCE));
    }

    @Test
    void rejectsLowConfidenceWithoutPretendingSuccess() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path source = createBlankImage();
        Path binary = fakeTesseract("20.0", "uncertain", false);
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.60d, 0.80d);
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        Path output = temp.resolve("low.txt");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter.convert(input(source), temp.resolve("low-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_LOW_CONFIDENCE", failure.code());
        assertTrue(Files.notExists(output));
    }

    @Test
    void reportsStableTimeoutCodeAndTerminatesProcess() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path source = createBlankImage();
        Path binary = fakeTesseract("90.0", "late", true);
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofMillis(100),
                1, 0.20d, 0.80d);
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, settings);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter.convert(input(source), temp.resolve("timeout-work"), temp.resolve("timeout.txt"),
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_TIMEOUT", failure.code());
    }

    @Test
    void mapsOcrCoordinatesBackToPhysicalPage() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path source = createBlankImage();
        Path binary = fakeTesseract("95.0", "vertical", false);
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, settings);

        var result = converter.recognizeLayoutResult(source, temp.resolve("layout-work"), 3,
                new com.fuyue.formatconverter.model.Rect(10d, 20d, 210d, 297d), ParseLimits.defaults());

        assertEquals(1, result.blocks().size());
        var block = result.blocks().get(0);
        assertEquals(3, block.pageNumber());
        assertTrue(block.box().x() >= 10d && block.box().right() <= 220d);
        assertTrue(block.box().y() >= 20d && block.box().bottom() <= 317d);
    }

    @Test
    void imageToWordUsesEditableDocumentModelTextAndConfidence() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path source = createBlankImage();
        Path binary = fakeTesseract("93.0", "EDITABLE OCR WORD", false);
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        var converter = new ImageOcrToDocxConverter(DocumentFormat.PNG, settings,
                new PageLayoutAnalyzer(), new PoiDocxRenderer());
        Path output = temp.resolve("ocr.docx");

        ConversionOutput converted = converter.convert(input(source), temp.resolve("docx-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(output))) {
            String text = document.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", String::concat);
            assertTrue(text.contains("EDITABLE OCR WORD"), text);
            assertTrue(document.getAllPictures().isEmpty(), "OCR Word must contain real text, not a page image");
        }
        assertTrue(converted.warnings().stream()
                .anyMatch(warning -> warning.code() == WarningCode.OCR_APPLIED
                        && warning.confidence() != null && warning.confidence() > 0.9d));
    }

    @Test
    void rejectsImageAboveDedicatedOcrPixelLimitBeforeStartingEngine() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path source = createBlankImage();
        Path binary = fakeTesseract("95.0", "should not run", false);
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d, 100L, temp.resolve("pixel-limit-lock"));
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, settings);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter.convert(input(source), temp.resolve("pixel-work"), temp.resolve("pixel.txt"),
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_RESOURCE_EXHAUSTED", failure.code());
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

    private Path createBlankImage() throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        Path source = temp.resolve("blank-" + System.nanoTime() + ".png");
        ImageIO.write(image, "png", source.toFile());
        return source;
    }

    private Path fakeTesseract(String confidence, String text, boolean sleep) throws Exception {
        Path binary = temp.resolve("fake-tesseract-" + System.nanoTime());
        String safeText = text.replace("'", "'\\''");
        String script = "#!/bin/sh\n" +
                (sleep ? "sleep 2\n" : "") +
                "base=\"$2\"\n" +
                "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n" +
                "printf '5\\t1\\t1\\t1\\t1\\t1\\t20\\t10\\t150\\t60\\t" + confidence + "\\t" + safeText + "\\n' >> \"${base}.tsv\"\n";
        Files.writeString(binary, script);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "image/png", Files.size(source), source);
    }
}
