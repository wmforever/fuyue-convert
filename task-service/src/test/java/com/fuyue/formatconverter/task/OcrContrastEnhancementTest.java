package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.WarningCode;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrContrastEnhancementTest {
    @TempDir Path temp;

    @Test
    void interpolationMatchesBaselinePixelsAtPartialTilesAndNarrowEdges() throws Exception {
        // Frozen from e3ffde1: includes alpha, gradients, low-contrast strokes,
        // one-dimensional images, partial tiles and extrapolated page edges.
        int[][] sizes = {{1, 83}, {83, 1}, {31, 29}, {137, 91}, {257, 259}, {1400, 1000}};
        String[] expected = {null,
                "5e1de758e62aea3ab32eb8d7a7daa0fc1035b6ab662a98137c4c7d216fb74a64",
                "20145ab3173e18b4143bfdce3b0730e18199904dbb0b429cc902bc2e649a51fc",
                "a546498ec11f5acb0382f50b2b625d163f0a22cc3e4ab6b96ae7b9cb8d6c57f2",
                "4b72f38d10e18b60de173fbb5d4f42d385576315f8d2fab23f907833995084b7",
                "ae202966babaf85564908f336705bd6cdd141f3667547a89d5ea6d0ebc188ddd"};
        for (int index = 0; index < sizes.length; index++) {
            int width = sizes[index][0], height = sizes[index][1];
            BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int gray = 110 + x * 70 / width + y * 30 / height;
                if (x % 37 > 7 && x % 37 < 23 && y % 41 > 13 && y % 41 < 26) gray -= 25;
                int alpha = (x + y) % 17 == 0 ? 180 : 255;
                source.setRGB(x, y, (alpha << 24) | (gray << 16) | (gray << 8) | gray);
            }
            BufferedImage output = OcrContrastEnhancer.enhance(source);
            if (expected[index] == null) {
                assertNull(output);
            } else {
                assertNotNull(output);
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    digest.update((byte) output.getRaster().getSample(x, y, 0));
                }
                assertEquals(expected[index], java.util.HexFormat.of().formatHex(digest.digest()),
                        width + "x" + height + " output pixels must remain identical");
                output.flush();
            }
            source.flush();
        }
    }

    @Test
    void removesGrayShadowWithoutMovingInkOrChangingSourcePixels() {
        BufferedImage image = new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++) {
            int background = 80 + x / 4;
            boolean ink = y >= 110 && y < 126 && x % 32 >= 8 && x % 32 < 20;
            int gray = background - (ink ? 25 : 0);
            image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
        }
        int[] original = image.getRGB(0, 0, 512, 256, null, 0, 512);
        BufferedImage result = OcrContrastEnhancer.enhance(image);
        assertNotNull(result);
        assertEquals(512, result.getWidth());
        assertEquals(256, result.getHeight());
        for (int x = 10; x < 500; x += 32) {
            assertTrue(result.getRaster().getSample(x, 90, 0) >= 245, "paper shadow removed");
            assertTrue(result.getRaster().getSample(x, 116, 0) <= 55, "ink strengthened in its original location");
        }
        assertArrayEquals(original, image.getRGB(0, 0, 512, 256, null, 0, 512));
        image.flush(); result.flush();
    }

    @Test
    void skipsBlankAndTransparentPages() {
        BufferedImage transparent = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
        assertNull(OcrContrastEnhancer.enhance(transparent));
        var graphics = transparent.createGraphics();
        graphics.setColor(new Color(130, 130, 130));
        graphics.fillRect(0, 0, 120, 80);
        graphics.dispose();
        assertNull(OcrContrastEnhancer.enhance(transparent));
        transparent.flush();
    }

    @Test
    void enhancesSparseLowContrastInkWithoutTreatingThePageAsBlank() {
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(160, 160, 160)); graphics.fillRect(0, 0, 600, 400);
        graphics.setColor(new Color(130, 130, 130)); graphics.fillRect(180, 120, 60, 10);
        graphics.dispose();
        int originalInk = image.getRGB(200, 125), originalPaper = image.getRGB(200, 110);
        BufferedImage enhanced = OcrContrastEnhancer.enhance(image);
        assertNotNull(enhanced, "a small low-contrast label on a mostly empty page must still be enhanced");
        assertEquals(image.getWidth(), enhanced.getWidth()); assertEquals(image.getHeight(), enhanced.getHeight());
        assertTrue(enhanced.getRaster().getSample(200, 125, 0) < 55, "sparse ink remains visible at its source position");
        assertTrue(enhanced.getRaster().getSample(200, 110, 0) >= 245, "paper stays clear");
        assertEquals(originalInk, image.getRGB(200, 125)); assertEquals(originalPaper, image.getRGB(200, 110));
        image.flush(); enhanced.flush();
    }

    @Test
    void isolatedDarkPixelsDoNotTriggerSparseInkRecovery() {
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(new Color(160, 160, 160));
        graphics.fillRect(0, 0, 600, 400); graphics.dispose();
        for (int index = 0; index < 20; index++) image.setRGB(20 + index * 25, 40 + index * 13, Color.BLACK.getRGB());
        assertNull(OcrContrastEnhancer.enhance(image), "isolated dirt on a near-empty page is not enough evidence for a retry");
        image.flush();
    }

    @Test
    void skipsExtremeAspectRatioWithoutAllocatingWideHistogramArrays() {
        BufferedImage image = new BufferedImage(32769, 1, BufferedImage.TYPE_INT_RGB);
        assertNull(OcrContrastEnhancer.enhance(image));
        image.flush();
    }

    @Test
    void acceptsFullerBetterResultPreservesSourceCoordinatesAndReportsWarning() throws Exception {
        Path source = shadedImage();
        byte[] original = Files.readAllBytes(source);
        var converter = fake("60", "OCR result 2026", "94", "OCR result 2026", "");
        Path work = temp.resolve("accepted");
        var result = converter.recognizeLayoutResult(source, work, 4, new Rect(10, 20, 240, 160), ParseLimits.defaults());
        assertTrue(result.imageEnhanced());
        assertEquals(0.94, result.confidence(), 0.001);
        assertEquals(new Rect(30, 40, 160, 40), result.blocks().get(0).box());
        assertTrue(converter.warningsFor(result, 4, "第 4 页").stream()
                .anyMatch(warning -> warning.code() == WarningCode.OCR_IMAGE_ENHANCED && warning.pageNumber() == 4));
        assertArrayEquals(original, Files.readAllBytes(source));
        Path used = Path.of(Files.readString(work.resolve("enhanced-input-path")).strip());
        assertTrue(Files.notExists(used));
        BufferedImage received = ImageIO.read(work.resolve("enhanced-received.png").toFile());
        assertEquals(600, received.getWidth()); assertEquals(400, received.getHeight());
        received.flush();
    }

    @Test
    void rejectsSparseHigherConfidenceResultAndKeepsOriginalText() throws Exception {
        var converter = fake("60", "Complete original text 2026", "99", "2026", "");
        var result = recognize(converter, "sparse");
        assertFalse(result.imageEnhanced());
        assertEquals("Complete original text 2026", result.blocks().get(0).text());
        assertEquals(0.60, result.confidence(), 0.001);
    }

    @Test
    void protectsReliableWordsEvenIfLowConfidenceWordsIncreaseCandidateLength() throws Exception {
        var converter = fake("60", "low accuracy", "99", "low accuracy extra filler", "");
        var original = recognize(converter, "reliable");
        var block = original.blocks().get(0);
        var reliableBlock = new com.fuyue.formatconverter.model.TextBlock(block.id(), block.pageNumber(), block.box(),
                "INVOICE 2026 low accuracy", block.baselineY(), block.style(), block.zOrder(), 0, 0,
                java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY,
                java.util.List.of(new com.fuyue.formatconverter.model.TextBlock.OcrWord(block.box(), "INVOICE 2026", 0.96)));
        assertFalse(TesseractOcrConverter.preferEnhanced(
                new TesseractOcrConverter.RecognitionResult(java.util.List.of(reliableBlock), 0.60, 3), original, 0.35));
    }

    @Test
    void skipsEnhancementForHighConfidenceOriginal() throws Exception {
        var converter = fake("95", "original", "99", "enhanced", "exit 1");
        var result = recognize(converter, "high");
        assertFalse(result.imageEnhanced());
        assertTrue(Files.notExists(temp.resolve("high/enhanced-input-path")));
    }

    @Test
    void retryFailureAndTimeoutKeepUsableOriginalAndReleaseTemporaryPng() throws Exception {
        for (String behavior : new String[]{"exit 1", "sleep 8"}) {
            var converter = fake("60", "usable original", "99", "enhanced", behavior);
            String name = behavior.startsWith("sleep") ? "timeout" : "failed";
            long started = System.nanoTime();
            var result = recognize(converter, name);
            assertFalse(result.imageEnhanced());
            assertEquals("usable original", result.blocks().get(0).text());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 7, "retry stays within page time budget");
            Path used = Path.of(Files.readString(temp.resolve(name + "/enhanced-input-path")).strip());
            assertTrue(Files.notExists(used));
        }
    }

    @Test
    void highConfidenceUncoveredInkWarnsWithoutLaunchingImpossibleGainRetry() throws Exception {
        Path source = uncoveredShadedImage(); byte[] original = Files.readAllBytes(source);
        var converter = fake("97", "original 2026", "99", "untrusted replacement", "exit 1");
        Path work = temp.resolve("uncovered-high");
        var result = converter.recognizeLayoutResult(source, work, 2, new Rect(10,20,300,200), ParseLimits.defaults());
        assertEquals("original 2026", result.blocks().get(0).text());
        assertEquals(.97, result.confidence(), .001);
        assertTrue(result.possibleTextOmission());
        assertFalse(result.imageEnhanced());
        assertTrue(Files.notExists(work.resolve("enhanced-input-path")));
        assertTrue(converter.warningsFor(result,2,"第 2 页").stream().anyMatch(w ->
                w.code()==WarningCode.OCR_POSSIBLE_TEXT_OMISSION && w.pageNumber()==2));
        assertArrayEquals(original,Files.readAllBytes(source));
    }

    @Test
    void acceptedEnhancementStillWarnsWhenFinalWordsLeaveShadedInkUncovered() throws Exception {
        Path source = uncoveredShadedImage();
        byte[] original = Files.readAllBytes(source);
        var converter = fake("60", "original 2026", "90", "original 2026 more lines", "");
        var result = converter.recognizeLayoutResult(source, temp.resolve("accepted-incomplete"), 1,
                new Rect(0, 0, 600, 400), ParseLimits.defaults());
        assertTrue(result.imageEnhanced());
        assertEquals("original 2026 more lines", result.blocks().get(0).text());
        var pixels = ImageIO.read(source.toFile());
        assertTrue(OcrCoverageProbe.hasUncoveredShadedInk(pixels, result.blocks(),
                new Rect(0, 0, 600, 400), System.nanoTime() + 1_000_000_000L));
        pixels.flush();
        assertTrue(result.possibleTextOmission(), "adoption cannot certify coverage of the selected words");
        var warning = converter.warningsFor(result, 1, "第1页").stream()
                .filter(w -> w.code() == WarningCode.OCR_POSSIBLE_TEXT_OMISSION).findFirst().orElseThrow();
        assertFalse(warning.message().contains("未采用"), "the full enhanced candidate was actually adopted");
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void acceptedEnhancementWithCompleteCoverageDoesNotWarn() throws Exception {
        Path source = uncoveredShadedImage();
        var converter = fake("60", "original 2026", "90", "original 2026 more lines", "", "",
                "0\\t0\\t600\\t400");
        var result = converter.recognizeLayoutResult(source, temp.resolve("accepted-covered"), 1,
                new Rect(0, 0, 600, 400), ParseLimits.defaults());
        assertTrue(result.imageEnhanced());
        assertFalse(result.possibleTextOmission());
        assertTrue(converter.warningsFor(result, 1, "第1页").stream()
                .noneMatch(w -> w.code() == WarningCode.OCR_POSSIBLE_TEXT_OMISSION));
    }

    @Test
    void acceptedEnhancementDoesNotExtendPageBudgetForFinalCoverageCheck() throws Exception {
        Path source = uncoveredShadedImage();
        var converter = fake("60", "original 2026", "90", "original 2026 more lines", "sleep 4.2");
        long started = System.nanoTime();
        var result = converter.recognizeLayoutResult(source, temp.resolve("accepted-budget"), 1,
                new Rect(0, 0, 600, 400), ParseLimits.defaults());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 6500);
        assertTrue(result.imageEnhanced());
        assertFalse(result.possibleTextOmission(), "optional probe is skipped with less than one second remaining");
    }

    @Test
    void coverageWarningDependsOnRemainingInkInsteadOfCandidateAdoption() throws Exception {
        Path source = uncoveredShadedImage();
        for (boolean accepted : new boolean[]{false,true}) {
            var converter=fake("88","original 2026","97",accepted?"original 2026 more lines":"original 20260 more lines","");
            var result=converter.recognizeLayoutResult(source,temp.resolve("uncovered-"+accepted),1,
                    new Rect(0,0,600,400),ParseLimits.defaults());
            assertEquals(accepted,result.imageEnhanced());
            assertTrue(result.possibleTextOmission(), "both selected word sets leave the lower ink bands uncovered");
            if(!accepted) assertEquals("original 2026",result.blocks().get(0).text());
        }
    }

    @Test
    void sharesPageBudgetAndSkipsRetryWhenOriginalRecognitionUsedIt() throws Exception {
        var converter = fake("60", "usable original", "99", "enhanced", "sleep 8", "sleep 4.2");
        try {
            var result = recognize(converter, "budget");
            assertFalse(result.imageEnhanced());
            assertEquals("usable original", result.blocks().get(0).text());
        } catch (ConversionFailureException failure) {
            // A loaded host may exhaust the original process budget before its 4.2 s sleep ends.
            // It must still report the stable original timeout rather than launching a retry.
            assertEquals("OCR_TIMEOUT", failure.code());
        }
        assertTrue(Files.notExists(temp.resolve("budget/enhanced-input-path")));
    }

    @Test
    void requiresMeaningfulConfidenceGainAndReliableWordsInOriginalOrder() {
        Rect box = new Rect(0, 0, 100, 20);
        var block = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, "Invoice 2026 total 12345", 16,
                null, 0, 0, 0, java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY,
                java.util.List.of(new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, "Invoice 2026", 0.96),
                        new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, "total 12345", 0.95)));
        var original = new TesseractOcrConverter.RecognitionResult(java.util.List.of(block), 0.6, 4);
        assertFalse(TesseractOcrConverter.preferEnhanced(original,
                new TesseractOcrConverter.RecognitionResult(java.util.List.of(block), 0.64, 4), 0.35));
        assertTrue(TesseractOcrConverter.preferEnhanced(original,
                new TesseractOcrConverter.RecognitionResult(java.util.List.of(block), 0.9, 4), 0.35));
        var reversed = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, "total 12345 Invoice 2026", 16, null, 0);
        assertFalse(TesseractOcrConverter.preferEnhanced(original,
                new TesseractOcrConverter.RecognitionResult(java.util.List.of(reversed), 0.9, 4), 0.35));
    }

    @Test
    void rejectsChangedReliableAmountsAndAppendedDigits() {
        Rect box = new Rect(0, 0, 100, 20);
        for (String[] pair : new String[][]{{"123.45", "12345"}, {"2026", "20260"}}) {
            var originalBlock = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, pair[0], 16, null,
                    0, 0, 0, java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY,
                    java.util.List.of(new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, pair[0], 0.96)));
            var candidateBlock = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, pair[1], 16, null, 0);
            assertFalse(TesseractOcrConverter.preferEnhanced(
                    new TesseractOcrConverter.RecognitionResult(java.util.List.of(originalBlock), 0.6, 1),
                    new TesseractOcrConverter.RecognitionResult(java.util.List.of(candidateBlock), 0.9, 1), 0.35));
        }
    }

    @Test
    void rejectsNumericTokenExtensionsAcrossDecimalSignCurrencyAndPercentBoundaries() {
        Rect box = new Rect(0, 0, 100, 20);
        for (String[] pair : new String[][]{{"95", ".95"}, {".95", "0.95"},
                {"95", "-95"}, {"95", "95%"}, {"$95", "$95.0"}, {"９５", "．９５"},
                {"95", "$95"}, {"95", "(95)"}, {"95", "95,00"}, {"95", "95‰"},
                {"٫٩٥", "٠٫٩٥"}, {"USD1\u202f234.50", "USD1234.50"},
                {"1'234", "01'234"}, {"1,234.50", "1,234.500"}}) {
            var originalBlock = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, pair[0], 16, null,
                    0, 0, 0, java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY,
                    java.util.List.of(new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, pair[0], 0.96)));
            var candidateBlock = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, pair[1], 16, null, 0);
            assertFalse(TesseractOcrConverter.preferEnhanced(
                    new TesseractOcrConverter.RecognitionResult(java.util.List.of(originalBlock), .60, 1),
                    new TesseractOcrConverter.RecognitionResult(java.util.List.of(candidateBlock), .90, 1), .35),
                    pair[0] + " must not become " + pair[1]);
        }
    }

    @Test
    void retainsUnchangedNumericSurfacesAndSeparateAmountsDuringEnhancement() {
        Rect box = new Rect(0, 0, 100, 20);
        for (String value : java.util.List.of(".95", "-.95", "$0.95", "95%", "９５．００", "1\u202f234.50")) {
            var block = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, value, 16, null,
                    0, 0, 0, java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY,
                    java.util.List.of(new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, value, .96)));
            assertTrue(TesseractOcrConverter.preferEnhanced(
                    new TesseractOcrConverter.RecognitionResult(java.util.List.of(block), .60, 1),
                    new TesseractOcrConverter.RecognitionResult(java.util.List.of(block), .90, 1), .35));
        }
        var original = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, "12 34", 16, null,
                0, 0, 0, java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY,
                java.util.List.of(new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, "12", .96),
                        new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, "34", .96)));
        var merged = new com.fuyue.formatconverter.model.TextBlock("line", 1, box, "1234", 16, null, 0);
        assertFalse(TesseractOcrConverter.preferEnhanced(
                new TesseractOcrConverter.RecognitionResult(java.util.List.of(original), .60, 2),
                new TesseractOcrConverter.RecognitionResult(java.util.List.of(merged), .90, 1), .35));
    }

    @Test
    void recoversNoTextButDoesNotAcceptCandidateBelowMinimumConfidence() throws Exception {
        var recovered = recognize(fake("-1", "", "92", "recovered 2026", ""), "empty");
        assertTrue(recovered.imageEnhanced());
        assertEquals("recovered 2026", recovered.blocks().get(0).text());
        var rejected = recognize(fake("-1", "", "30", "uncertain 2026", ""), "minimum");
        assertTrue(rejected.blocks().isEmpty());
        assertFalse(rejected.imageEnhanced());
    }

    @Test
    void bundledEngineRecoversShadedLowContrastPrintedText() throws Exception {
        var capability = TesseractOcrConverter.detectConfigured();
        assumeTrue(capability.available() && capability.settings().bundled() && capability.availableLanguages().contains("eng"));
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1200, 800);
        graphics.setColor(Color.BLACK);
        // Use the same licensed font on every platform: logical SansSerif maps
        // to different glyphs/spacing and can change Tesseract's column split.
        try (var font = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            assertNotNull(font);
            graphics.setFont(Font.createFont(Font.TRUETYPE_FONT, font).deriveFont(32f));
        }
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String sentence = "Invoice OCR 2026 total amount 12345";
        for (int line = 0; line < 8; line++) graphics.drawString(sentence, 90, 100 + line * 80);
        graphics.dispose();
        for (int y = 0; y < 800; y++) for (int x = 0; x < 1200; x++) {
            int ink = 255 - (image.getRGB(x, y) & 255);
            int background = 45 + (int) (170 * x / 1199.0);
            int value = background - ink * 18 / 255;
            image.setRGB(x, y, new Color(value, value, value).getRGB());
        }
        Path source = temp.resolve("shaded-native.png"); ImageIO.write(image, "png", source.toFile()); image.flush();
        var settings = capability.settings();
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        Path work = temp.resolve("native-work");
        var result = converter.recognizeLayoutResult(source, work, 1, new Rect(0, 0, 1200, 800), ParseLimits.defaults());
        String text = result.blocks().stream().map(block -> block.text()).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.toLowerCase(Locale.ROOT).contains("invoice ocr 2026 total amount 12345"), text);
        assertTrue(result.confidence() >= settings.minimumConfidence());
        assertTrue(result.imageEnhanced(), "faint shading fixture must exercise the native enhancement path");
        assertEquals(8, result.blocks().size());

        Path imageWord = temp.resolve("shaded-image.docx");
        var imageOutput = new ImageOcrToDocxConverter(DocumentFormat.PNG, settings,
                new com.fuyue.formatconverter.table.PageLayoutAnalyzer(), new com.fuyue.formatconverter.docx.PoiDocxRenderer())
                .convert(new ConversionInput("shaded.png", "image/png", Files.size(source), source),
                        temp.resolve("image-word-work"), imageWord, ParseLimits.defaults(), (stage, percent) -> { });
        assertTrue(imageOutput.warnings().stream().anyMatch(w -> w.code() == WarningCode.OCR_IMAGE_ENHANCED));
        assertWordText(imageWord, sentence, false);

        Path pdf = temp.resolve("shaded-scan.pdf"), pdfWord = temp.resolve("shaded-pdf.docx");
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(288, 192));
            document.addPage(page);
            BufferedImage scan = ImageIO.read(source.toFile());
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, scan),
                        0, 0, 288, 192);
            } finally { scan.flush(); }
            document.save(pdf.toFile());
        }
        var pdfOutput = new PdfToDocxConverter(new PdfLayoutParser(),
                new com.fuyue.formatconverter.table.PageLayoutAnalyzer(), new com.fuyue.formatconverter.docx.PoiDocxRenderer(),
                new PdfOcrSupport(settings)).convert(new ConversionInput("shaded-scan.pdf", "application/pdf", Files.size(pdf), pdf),
                temp.resolve("pdf-word-work"), pdfWord, ParseLimits.defaults(), (stage, percent) -> { });
        assertTrue(pdfOutput.warnings().stream().anyMatch(w -> w.code() == WarningCode.OCR_IMAGE_ENHANCED));
        assertWordText(pdfWord, sentence, true);
        String qa = System.getProperty("format.converter.ocr-enhancement.qa-directory", "");
        if (!qa.isBlank()) {
            Path directory = Path.of(qa); Files.createDirectories(directory);
            Files.copy(source, directory.resolve("shaded-source.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            for (String name : new String[]{"tesseract-page-0001.tsv", "tesseract-enhanced-page-0001.tsv"}) {
                if (Files.exists(work.resolve(name))) Files.copy(work.resolve(name), directory.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(imageWord, directory.resolve("image-converted.docx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(pdf, directory.resolve("scan-source.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(pdfWord, directory.resolve("scan-converted.docx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(directory.resolve("recognized.txt"), text);
            Files.writeString(directory.resolve("native-result.txt"), "imageEnhanced=" + result.imageEnhanced()
                    + "\nconfidence=" + result.confidence() + "\nwords=" + result.wordCount() + "\n");
        }
    }

    private void assertWordText(Path path, String sentence, boolean expectedBackground) throws Exception {
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(Files.newInputStream(path))) {
            var xml = org.apache.poi.util.XMLHelper.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    document.getDocument().xmlText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var nodes = xml.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < nodes.getLength(); index++) text.append(nodes.item(index).getTextContent());
            assertEquals(sentence.repeat(8), text.toString());
            assertEquals(expectedBackground, !document.getAllPictures().isEmpty());
        }
    }

    @Test
    void bundledEngineRecoversChineseFaintTextWithDigitsAndPunctuation() throws Exception {
        var capability = TesseractOcrConverter.detectConfigured();
        assumeTrue(capability.available() && capability.settings().bundled() && capability.availableLanguages().contains("chi_sim"));
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1200, 800);
        graphics.setColor(Color.BLACK);
        Font chinese;
        try (var font = getClass().getResourceAsStream("/fonts/DroidSansFallback.ttf")) {
            assertNotNull(font);
            chinese = Font.createFont(Font.TRUETYPE_FONT, font).deriveFont(42f);
        }
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String[] sentences = {"文档转换测试，金额12345元。", "本地识别中文，无需上传文件。",
                "保留原始图像，文字可以编辑。", "扫描页面较暗，自动增强对比。",
                "请核对转换结果，检查数字标点。", "使用简体中文模型处理印刷文档。",
                "转换完成以后，保存并打开文档。", "图片位置保持不变，内容需要复核。"};
        for (int line = 0; line < sentences.length; line++) {
            String sentence = sentences[line];
            java.text.AttributedString label = new java.text.AttributedString(sentence);
            label.addAttribute(java.awt.font.TextAttribute.FONT, chinese);
            int digits = sentence.indexOf("12345");
            if (digits >= 0) label.addAttribute(java.awt.font.TextAttribute.FONT,
                    new Font(Font.SANS_SERIF, Font.PLAIN, 42), digits, digits + 5);
            new java.awt.font.TextLayout(label.getIterator(), graphics.getFontRenderContext())
                    .draw(graphics, 90, 100 + line * 80);
        }
        graphics.dispose();
        for (int y = 0; y < 800; y++) for (int x = 0; x < 1200; x++) {
            int ink = 255 - (image.getRGB(x, y) & 255);
            int value = 45 + (int) (170 * x / 1199.0) - ink * 18 / 255;
            image.setRGB(x, y, new Color(value, value, value).getRGB());
        }
        Path source = temp.resolve("chinese-shaded.png"); ImageIO.write(image, "png", source.toFile()); image.flush();
        var result = new TesseractOcrConverter(DocumentFormat.PNG, capability.settings()).recognizeLayoutResult(
                source, temp.resolve("chinese-work"), 1, new Rect(0, 0, 1200, 800), ParseLimits.defaults());
        String recognized = result.blocks().stream().map(block -> block.text()).reduce("", String::concat);
        String qa = System.getProperty("format.converter.ocr-enhancement.qa-directory", "");
        if (!qa.isBlank()) {
            Path directory = Path.of(qa); Files.createDirectories(directory);
            Files.copy(source, directory.resolve("chinese-shaded-source.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            BufferedImage pixels = ImageIO.read(source.toFile());
            BufferedImage enhanced = OcrContrastEnhancer.enhance(pixels);
            assertNotNull(enhanced); ImageIO.write(enhanced, "png", directory.resolve("chinese-enhanced-input.png").toFile());
            pixels.flush(); enhanced.flush();
            Files.writeString(directory.resolve("chinese-recognized.txt"), recognized);
            Files.writeString(directory.resolve("chinese-native-result.txt"), "imageEnhanced=" + result.imageEnhanced()
                    + "\nconfidence=" + result.confidence() + "\nwords=" + result.wordCount() + "\n");
            for (String name : new String[]{"tesseract-page-0001.tsv", "tesseract-enhanced-page-0001.tsv"}) {
                if (Files.exists(temp.resolve("chinese-work").resolve(name))) Files.copy(temp.resolve("chinese-work").resolve(name),
                        directory.resolve("chinese-" + name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        assertEquals(String.join("", sentences), recognized.replace(" ", ""));
        assertTrue(result.imageEnhanced());
    }

    private TesseractOcrConverter.RecognitionResult recognize(TesseractOcrConverter converter, String name) throws Exception {
        return converter.recognizeLayoutResult(shadedImage(), temp.resolve(name), 1,
                new Rect(0, 0, 600, 400), ParseLimits.defaults());
    }

    private Path shadedImage() throws Exception {
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(new Color(160, 160, 160));
        graphics.fillRect(0, 0, 600, 400); graphics.setColor(new Color(130, 130, 130));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 36)); graphics.drawString("OCR result 2026", 50, 140);
        graphics.dispose(); Path source = temp.resolve("shaded.png"); ImageIO.write(image, "png", source.toFile()); image.flush();
        return source;
    }

    private Path uncoveredShadedImage() throws Exception {
        BufferedImage image=new BufferedImage(600,400,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<400;y++) for(int x=0;x<600;x++) {
            int gray=90+x*130/600;
            if(y>=60 && y<350 && y%60<10 && x>60 && x<450 && x%20<12) gray=0;
            image.setRGB(x,y,new Color(gray,gray,gray).getRGB());
        }
        Path path=temp.resolve("uncovered-shaded.png");ImageIO.write(image,"png",path.toFile());image.flush();return path;
    }

    private TesseractOcrConverter fake(String confidence, String text, String enhancedConfidence,
                                      String enhancedText, String behavior) throws Exception {
        return fake(confidence, text, enhancedConfidence, enhancedText, behavior, "");
    }

    private TesseractOcrConverter fake(String confidence, String text, String enhancedConfidence,
                                      String enhancedText, String behavior, String originalBehavior) throws Exception {
        return fake(confidence, text, enhancedConfidence, enhancedText, behavior, originalBehavior,
                "50\\t50\\t400\\t100");
    }

    private TesseractOcrConverter fake(String confidence, String text, String enhancedConfidence,
                                      String enhancedText, String behavior, String originalBehavior,
                                      String coordinates) throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path binary = temp.resolve("fake-" + System.nanoTime());
        String script = "#!/bin/sh\nbase=\"$2\"\nconfidence=" + confidence + "\ntext='" + text + "'\n"
                + "case \"$1\" in *tesseract-enhanced-*)\n"
                + "printf '%s\\n' \"$1\" > \"$(dirname \"$2\")/enhanced-input-path\"\n"
                + "cp \"$1\" \"$(dirname \"$2\")/enhanced-received.png\"\n"
                + behavior + "\nconfidence=" + enhancedConfidence + "\ntext='" + enhancedText + "'\n;; *) " + originalBehavior + "\n;; esac\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t" + coordinates + "\\t%s\\t%s\\n' \"$confidence\" \"$text\" >> \"${base}.tsv\"\n";
        Files.writeString(binary, script); assertTrue(binary.toFile().setExecutable(true));
        return new TesseractOcrConverter(DocumentFormat.PNG, new TesseractOcrConverter.Settings(binary, "eng", "fake",
                Duration.ofSeconds(5), 1, 0.35, 0.75, 25_000_000L, temp.resolve("locks")));
    }
}
