package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrDeskewTest {
    @TempDir Path temp;
    private static final String[] LINES = {
            "Invoice OCR 2026 total amount 12345", "Please verify customer number 80421",
            "Document conversion preserves editable text", "Local processing keeps source files private",
            "Table fields include date quantity and price", "Scan quality improves with straight text lines",
            "The final amount is 9876 and invoice is 2026", "Review each converted paragraph before sharing"};

    @Test void detectsBothDirectionsAndInverseMapsExpandedCanvasWithoutChangingSource() throws Exception {
        for (int angle : new int[]{-6, 6}) {
            BufferedImage source = page(angle);
            int[] original = source.getRGB(0, 0, 1400, 1000, null, 0, 1400);
            try (var prepared = OcrDeskew.prepare(source, 25_000_000, System.nanoTime() + Duration.ofSeconds(3).toNanos())) {
                assertNotNull(prepared);
                assertEquals(angle, prepared.degrees(), .5);
                assertTrue(prepared.image().getWidth() > source.getWidth());
                assertTrue(prepared.image().getHeight() > source.getHeight());
                var forward = prepared.inverse().createInverse();
                var center = forward.transform(new java.awt.geom.Point2D.Double(300, 400), null);
                Rect corrected = new Rect(center.getX() * .1 - 2, center.getY() * .1 - .5, 4, 1);
                Rect mapped = prepared.originalBounds(corrected, .1, .1, new Rect(10, 20, 140, 100));
                assertEquals(40, mapped.center().x(), 1e-8);
                assertEquals(60, mapped.center().y(), 1e-8);
                assertTrue(mapped.width() >= 4);
                assertArrayEquals(original, source.getRGB(0, 0, 1400, 1000, null, 0, 1400));
            }
            source.flush();
        }
    }

    @Test void boundsProjectionRowsForInkAtTheBottomRightOfWideImages() {
        var image = new BufferedImage(700, 170, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 700, 170);
        graphics.setColor(Color.BLACK); graphics.fillRect(500, 155, 180, 12);
        graphics.dispose();
        assertDoesNotThrow(() -> OcrDeskew.detect(image, System.nanoTime() + Duration.ofSeconds(3).toNanos()));
        image.flush();
    }

    @Test void skipsUprightBlankSparseGridOutOfRangeAndExpiredBudget() throws Exception {
        BufferedImage blank = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
        var graphics = blank.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1400, 1000); graphics.dispose();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        assertEquals(0, OcrDeskew.detect(blank, deadline));
        for (int index = 0; index < 20; index++) blank.setRGB(20 + index * 25, 40 + index * 13, Color.BLACK.getRGB());
        assertEquals(0, OcrDeskew.detect(blank, deadline));
        graphics = blank.createGraphics(); graphics.setColor(Color.BLACK);
        for (int x = 100; x < 1300; x += 150) graphics.drawLine(x, 100, x, 900);
        for (int y = 100; y < 900; y += 100) graphics.drawLine(100, y, 1300, y);
        graphics.dispose();
        assertEquals(0, OcrDeskew.detect(blank, deadline)); blank.flush();
        for (int angle : new int[]{0, 15}) {
            BufferedImage source = page(angle);
            assertEquals(0, OcrDeskew.detect(source, System.nanoTime() + Duration.ofSeconds(3).toNanos()));
            assertNull(OcrDeskew.prepare(source, 25_000_000, System.nanoTime() - 1));
            source.flush();
        }
        BufferedImage tilted = page(6);
        assertNull(OcrDeskew.prepare(tilted, 1_400_000, System.nanoTime() + Duration.ofSeconds(3).toNanos()),
                "expanded input must remain within the OCR pixel budget");
        tilted.flush();
    }

    @Test void preservesReliableNumericConflictAndRejectsLowConfidenceOrLostReliableWords() {
        Rect box = new Rect(10, 10, 30, 10);
        var original = result("80424", .95, box);
        var candidate = result("80421", .97, box);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), 6, new AffineTransform())) {
            var selected = OcrDeskewSelection.select(original, candidate, prepared, new Rect(0, 0, 100, 100), 100, 100, .35);
            assertEquals("80424", selected.blocks().get(0).text());
            assertEquals(6, selected.deskewDegrees());
            assertFalse(selected.conflicts().isEmpty());
            assertEquals(box, selected.blocks().get(0).ocrWords().get(0).box());
            assertSame(original, OcrDeskewSelection.select(original, result("80421", .2, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original, result("80421", .97, new Rect(60, 60, 30, 10)),
                    prepared, new Rect(0, 0, 100, 100), 100, 100, .35));
        }
    }

    @Test void bundledEngineRecoversMissingLinesWithSourceGeometryAndReviewWarnings() throws Exception {
        var capability = TesseractOcrConverter.detectConfigured();
        assumeTrue(capability.available() && capability.settings().bundled(), "requires pinned bundled OCR runtime");
        for (int angle : new int[]{0, -6, 6}) {
            BufferedImage source = page(angle); Path path = temp.resolve("page-" + angle + ".png");
            ImageIO.write(source, "png", path.toFile()); source.flush();
            byte[] original = Files.readAllBytes(path);
            var converter = new TesseractOcrConverter(DocumentFormat.PNG, capability.settings());
            var selected = converter.recognizeLayoutResult(path, temp.resolve("work-" + angle), 1,
                    new Rect(10, 20, 140, 100), ParseLimits.defaults(), true);
            String text = selected.blocks().stream().map(TextBlock::text).reduce("", (a, b) -> a + b).replaceAll("\\s", "").toLowerCase();
            assertTrue(text.contains("revieweachconvertedparagraphbeforesharing"), text);
            assertTrue(text.contains("9876"), text);
            assertEquals(angle == 0, selected.deskewDegrees() == 0);
            if (angle != 0) assertTrue(converter.warningsFor(selected, 1, "page").stream().anyMatch(w -> w.code() == WarningCode.OCR_DESKEW_APPLIED));
            for (var line : selected.blocks()) for (var word : line.ocrWords()) {
                assertTrue(new Rect(10, 20, 140, 100).contains(word.box().center(), 0));
            }
            assertArrayEquals(original, Files.readAllBytes(path));
            try (var files = Files.list(temp.resolve("work-" + angle))) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith("tesseract-deskew-") && file.toString().endsWith(".png")));
            }
        }
    }

    @Test void optionalDeskewFailureAndTimeoutKeepOriginalAndDeleteTemporaryInput() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"));
        for (String behavior : List.of("exit 23", "sleep 8")) {
            var converter = fake("eng", behavior, "", Duration.ofSeconds(2));
            Path image = writePage(6), work = temp.resolve("failed-" + behavior.replace(' ', '-'));
            long started = System.nanoTime();
            var result = converter.recognizeLayoutResult(image, work, 1,
                    new Rect(0, 0, 140, 100), ParseLimits.defaults(), true);
            assertEquals("usable original 2026", result.blocks().get(0).text());
            assertEquals(0, result.deskewDegrees());
            assertTrue(Files.exists(work.resolve("deskew-attempted")), "the optional process must actually run");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5,
                    "retry cannot receive a fresh page timeout");
            try (var files = Files.list(work)) {
                assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith("tesseract-deskew-")
                        && file.toString().endsWith(".png")));
            }
        }
    }

    @Test void doesNotRetryLayoutVerticalAnisotropicOrNearlyExpiredPages() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"));
        Path image = writePage(6);
        var ordinary = fake("eng", "exit 23", "", Duration.ofSeconds(3));
        var vertical = fake("eng+chi_sim_vert", "exit 23", "", Duration.ofSeconds(3));
        var nearlyExpired = fake("eng", "exit 23", "sleep 2.2", Duration.ofSeconds(3));
        Path layout = temp.resolve("layout"), anisotropic = temp.resolve("anisotropic"),
                verticalWork = temp.resolve("vertical"), expired = temp.resolve("expired");
        ordinary.recognizeLayoutResult(image, layout, 1, new Rect(0, 0, 140, 100), ParseLimits.defaults());
        ordinary.recognizeLayoutResult(image, anisotropic, 1, new Rect(0, 0, 280, 100), ParseLimits.defaults(), true);
        vertical.recognizeLayoutResult(image, verticalWork, 1, new Rect(0, 0, 140, 100), ParseLimits.defaults(), true);
        nearlyExpired.recognizeLayoutResult(image, expired, 1, new Rect(0, 0, 140, 100), ParseLimits.defaults(), true);
        for (Path work : List.of(layout, anisotropic, verticalWork, expired)) {
            assertFalse(Files.exists(work.resolve("deskew-attempted")), work.toString());
        }
    }

    @Test void rejectsEmptyNumericMismatchAndPreservesDecimalBoundariesWithExplicitConflict() {
        Rect box = new Rect(10, 10, 30, 10);
        try (var prepared = new OcrDeskew.Prepared(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), 6, new AffineTransform())) {
            var original = result("USD804.24", .96, box);
            var selected = OcrDeskewSelection.select(original, result("USD80424", .98, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35);
            assertEquals("USD804.24", selected.blocks().get(0).text());
            assertEquals(1, selected.conflicts().size());
            for (String numeric : List.of("USD-804.24", "USD８０４．２４")) {
                var sourceNumber = result(numeric, .96, box);
                var preserved = OcrDeskewSelection.select(sourceNumber, result("USD80424", .98, box), prepared,
                        new Rect(0, 0, 100, 100), 100, 100, .35);
                assertEquals(numeric, preserved.blocks().get(0).text());
                assertEquals(1, preserved.conflicts().size());
            }
            assertSame(original, OcrDeskewSelection.select(original, selected, prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35, System.nanoTime() - 1));
            var dense = new TesseractOcrConverter.RecognitionResult(original.blocks(), .96, 2000);
            assertSame(dense, OcrDeskewSelection.select(dense, dense, prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original, result("USDpaid", .98, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original,
                    new TesseractOcrConverter.RecognitionResult(List.of(), .99, 0), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
            assertSame(original, OcrDeskewSelection.select(original, result("USD804.24", .90, box), prepared,
                    new Rect(0, 0, 100, 100), 100, 100, .35));
        }
    }

    private Path writePage(int angle) throws Exception {
        BufferedImage source = page(angle);
        Path image = Files.createTempFile(temp, "deskew-input-", ".png");
        ImageIO.write(source, "png", image.toFile()); source.flush(); return image;
    }

    private TesseractOcrConverter fake(String languages, String behavior, String initialBehavior, Duration timeout) throws Exception {
        Path binary = Files.createTempFile(temp, "fake-deskew-", ".sh");
        String script = "#!/bin/sh\nbase=\"$2\"\ncase \"$1\" in *tesseract-deskew-*)\n"
                + "touch \"$(dirname \"$2\")/deskew-attempted\"\n" + behavior + "\n;; *) " + initialBehavior + "\n;; esac\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t50\\t50\\t400\\t100\\t95\\tusable original 2026\\n' >> \"${base}.tsv\"\n";
        Files.writeString(binary, script); assertTrue(binary.toFile().setExecutable(true));
        return new TesseractOcrConverter(DocumentFormat.PNG, new TesseractOcrConverter.Settings(binary, languages, "fake",
                timeout, 1, .35, .75, 25_000_000, temp.resolve("locks")));
    }

    private TesseractOcrConverter.RecognitionResult result(String text, double confidence, Rect box) {
        var block = new TextBlock("line", 1, box, text, box.bottom(), null, 1, 0, 0, List.of(), Transform2D.IDENTITY,
                List.of(new TextBlock.OcrWord(box, text, confidence)));
        return new TesseractOcrConverter.RecognitionResult(List.of(block), confidence, 1);
    }

    private BufferedImage page(int angle) throws Exception {
        BufferedImage image = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1400, 1000);
        graphics.rotate(Math.toRadians(angle), 700, 500);
        graphics.setColor(Color.BLACK); graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        try (var font = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            assertNotNull(font); graphics.setFont(Font.createFont(Font.TRUETYPE_FONT, font).deriveFont(32f));
        }
        for (int index = 0; index < LINES.length; index++) graphics.drawString(LINES[index], 150, 125 + index * 100);
        graphics.dispose(); return image;
    }
}
