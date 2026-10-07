package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fuyue.formatconverter.parser.ParseLimits;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Duration;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OcrRuledGridTest {
    @TempDir Path temp;
    private static final Rect PAGE = new Rect(10, 20, 300, 200);
    private BufferedImage image() {
        var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 600, 400); g.setColor(Color.BLACK);
        for (int x : new int[]{20, 300, 580}) g.fillRect(x, 100, 2, 202);
        for (int y : new int[]{100, 200, 300}) g.fillRect(20, y, 562, 2);
        g.fillRect(45, 135, 12, 15); g.dispose(); return image;
    }
    private OcrRuledGrid.Grid grid() { return Objects.requireNonNull(OcrRuledGrid.detect(image(), PAGE, Long.MAX_VALUE)); }
    private static TextBlock line(String id, String text, Rect box, double confidence) {
        return new TextBlock(id, 1, box, text, box.bottom(), FontStyle.defaults(), 1, 0, 0, List.of(),
                Transform2D.IDENTITY, List.of(new TextBlock.OcrWord(box, text, confidence)));
    }
    private static TesseractOcrConverter.RecognitionResult result(TextBlock... blocks) {
        var words = Arrays.stream(blocks).flatMap(b -> b.ocrWords().stream()).toList();
        return new TesseractOcrConverter.RecognitionResult(List.of(blocks), words.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0), words.size());
    }
    private static TextBlock in(OcrRuledGrid.Grid grid, int cell, String text, double confidence) {
        var box = grid.cells().get(cell).box();
        return line("c" + cell, text, new Rect(box.x() + 10, box.y() + 12, 40, 8), confidence);
    }
    private static TesseractOcrConverter.RecognitionResult source(OcrRuledGrid.Grid grid, String value) {
        return result(line("title", "Ledger 00783", new Rect(30, 35, 90, 8), .99),
                line("uncertain", "Ce", new Rect(35, 80, 240, 12), .2), in(grid, 2, value, .96));
    }
    private static List<TesseractOcrConverter.RecognitionResult> candidates(OcrRuledGrid.Grid grid, String value) {
        return List.of(result(in(grid, 0, "Column A", .98)), result(in(grid, 1, "Column B", .98)),
                result(in(grid, 2, value, .99)), result(in(grid, 3, "00077", .98)));
    }

    @Test void provesContinuousGridAndMapsCropsToOriginalOffsetCoordinatesWithoutChangingPixels() {
        var image = image(); var before = image.getRGB(0, 0, 600, 400, null, 0, 600);
        var grid = OcrRuledGrid.detect(image, PAGE, Long.MAX_VALUE);
        assertNotNull(grid); assertEquals(4, grid.cells().size());
        assertEquals(new Rect(21, 71, 139, 49), grid.cells().get(0).box());
        assertArrayEquals(before, image.getRGB(0, 0, 600, 400, null, 0, 600));
    }
    @Test void ineligibleRecognitionDoesNotScanSourcePixelsButGridRecoveryStillRuns() {
        var unreadable = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB) {
            @Override public int getRGB(int x, int y) {
                throw new AssertionError("ineligible pages must not scan pixels for grid recovery");
            }
        };
        var cross = new Rect(35, 80, 240, 12);
        for (var old : List.of(result(), result(line("reliable", "Ce", cross, .35)),
                result(line("number", "Ce 001", cross, .2)),
                new TesseractOcrConverter.RecognitionResult(
                        List.of(line("large", "Ce", cross, .2)), .2, 501))) {
            assertNull(OcrRuledGrid.detectForRecovery(unreadable, PAGE, old, Long.MAX_VALUE));
        }
        var old = source(grid(), "-00127.50");
        var detected = OcrRuledGrid.detectForRecovery(image(), PAGE, old, Long.MAX_VALUE);
        assertNotNull(detected);
        assertTrue(OcrRuledGrid.select(detected, old, candidates(detected, "-00127.50"),
                .35, Long.MAX_VALUE).ruledGridRecovery());
    }
    @Test void darkPaperBrokenRuleBlankAndExpiredAnalysisDoNotEstablishCells() {
        var dark = image(); var g = dark.createGraphics(); g.setColor(Color.DARK_GRAY); g.fillRect(0, 0, 600, 100); g.dispose();
        assertNull(OcrRuledGrid.detect(dark, PAGE, Long.MAX_VALUE));
        var broken = image(); broken.setRGB(300, 175, Color.WHITE.getRGB()); broken.setRGB(301, 175, Color.WHITE.getRGB());
        assertNull(OcrRuledGrid.detect(broken, PAGE, Long.MAX_VALUE));
        assertNull(OcrRuledGrid.detect(new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB), PAGE, Long.MAX_VALUE));
        assertNull(OcrRuledGrid.detect(image(), PAGE, 0));
    }
    @Test void retainsOriginalNumericWordsAndOutsideTextAndRecordsReplacedCrossRuleHypothesis() {
        var grid = grid(); var old = source(grid, "-00127.50");
        var selected = OcrRuledGrid.select(grid, old, candidates(grid, "-00127.50"), .35, Long.MAX_VALUE);
        assertNotSame(old, selected); assertTrue(selected.ruledGridRecovery()); assertFalse(selected.partialRecovery()); assertTrue(selected.possibleTextOmission());
        assertEquals(List.of("Ledger 00783", "Column A", "Column B", "-00127.50", "00077"), selected.blocks().stream().map(TextBlock::text).toList());
        assertSame(old.blocks().get(2).ocrWords().get(0), selected.blocks().get(3).ocrWords().get(0));
        assertEquals(old.blocks().get(0), selected.blocks().get(0));
        assertTrue(selected.conflicts().get(0).contains("Ce"));
        assertEquals(5, selected.blocks().stream().map(TextBlock::zOrder).distinct().count());
    }
    @Test void rejectsChangedSignsLeadingZeroesCurrencyAccountingAndNumericExtensions() {
        var grid = grid();
        for (var pair : List.of(new String[]{"-00127.50", "00127.50"}, new String[]{"00127.50", "127.50"},
                new String[]{"$127.50", "127.50"}, new String[]{"(127.50)", "127.50"},
                new String[]{"+12.50%", "+12.50"}, new String[]{"12.50‰", "12.50%"},
                new String[]{"１２７", "127"}, new String[]{"127.50", "127.50 1"})) {
            var old = source(grid, pair[0]);
            assertSame(old, OcrRuledGrid.select(grid, old, candidates(grid, pair[1]), .35, Long.MAX_VALUE), Arrays.toString(pair));
        }
    }
    @Test void refusesToReplaceReliableCrossRuleWordsOrAnyCrossRuleNumber() {
        var grid = grid();
        for (var word : List.of(line("cross", "Reliable", new Rect(35, 80, 240, 12), .9),
                line("cross", "Ce 001", new Rect(35, 80, 240, 12), .2))) {
            var old = result(word, in(grid, 2, "127.50", .96));
            assertSame(old, OcrRuledGrid.select(grid, old, candidates(grid, "127.50"), .35, Long.MAX_VALUE));
        }
    }
    @Test void rejectsMissingCellsUntrustedRecoveryNoGainAndExpiredSelection() {
        var grid = grid(); var old = source(grid, "127.50"); var good = candidates(grid, "127.50");
        assertSame(old, OcrRuledGrid.select(grid, old, good.subList(0, 3), .35, Long.MAX_VALUE));
        assertSame(old, OcrRuledGrid.select(grid, old, good, .35, 0));
        var bad = new ArrayList<>(good); bad.set(3, result(in(grid, 3, "00077", .74)));
        assertSame(old, OcrRuledGrid.select(grid, old, bad, .35, Long.MAX_VALUE));
        var high = new TesseractOcrConverter.RecognitionResult(old.blocks(), .99, old.wordCount());
        assertSame(high, OcrRuledGrid.select(grid, high, good, .35, Long.MAX_VALUE));
        bad.set(3, result()); assertSame(old, OcrRuledGrid.select(grid, old, bad, .35, Long.MAX_VALUE));
    }

    private TesseractOcrConverter fake(String mode, Duration timeout) throws Exception {
        Path engine = temp.resolve("engine-" + mode);
        Files.writeString(engine, """
                #!/bin/sh
                base="$2"
                printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > "${base}.tsv"
                case "$base" in
                *grid-p1-c1*) VALUE='Column A';;
                *grid-p1-c2*) VALUE='Column B';;
                *grid-p1-c3*) VALUE='127.50';;
                *grid-p1-c4*) VALUE='00077';;
                *)
                printf '5\\t1\\t1\\t1\\t1\\t1\\t40\\t30\\t180\\t16\\t99\\tLedger 00783\\n5\\t1\\t2\\t1\\t1\\t1\\t50\\t120\\t480\\t24\\t20\\tCe\\n5\\t1\\t3\\t1\\t1\\t1\\t42\\t226\\t80\\t16\\t96\\t127.50\\n' >> "${base}.tsv"
                exit 0;;
                esac
                """ + (mode.equals("failure") ? "exit 1\n" : mode.equals("slow") ? "sleep 6\n" : "")
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t20\\t24\\t80\\t16\\t99\\t%s\\n' \"$VALUE\" >> \"${base}.tsv\"\n");
        assertTrue(engine.toFile().setExecutable(true));
        return new TesseractOcrConverter(DocumentFormat.PNG, new TesseractOcrConverter.Settings(engine, "eng", "fake", timeout, 1, .35, .75));
    }
    @Test void productionCellRecognitionUsesOriginalCropsAndCleansThemOnSuccessAndFailure() throws Exception {
        Path input = temp.resolve("grid.png"); ImageIO.write(image(), "png", input.toFile()); byte[] before = Files.readAllBytes(input);
        for (String mode : List.of("success", "failure")) {
            Path work = temp.resolve(mode);
            var selected = fake(mode, Duration.ofSeconds(10)).recognizeLayoutResult(input, work, 1, PAGE, ParseLimits.defaults());
            assertEquals(mode.equals("success"), selected.ruledGridRecovery());
            assertEquals(mode.equals("success") ? List.of("Ledger 00783", "Column A", "Column B", "127.50", "00077")
                    : List.of("Ledger 00783", "Ce", "127.50"), selected.blocks().stream().map(TextBlock::text).toList());
            assertTrue(selected.blocks().stream().flatMap(b -> b.ocrWords().stream()).anyMatch(w -> w.text().equals("127.50") && w.box().equals(new Rect(31, 133, 40, 8))));
            try (var files = Files.list(work)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("tesseract-grid-") && p.toString().endsWith(".png"))); }
        }
        assertArrayEquals(before, Files.readAllBytes(input));
    }
    @Test void regionRetriesShareTheOriginalPageDeadlineAndCleanUpOnTimeout() throws Exception {
        Path input = temp.resolve("grid.png"); ImageIO.write(image(), "png", input.toFile()); Path work = temp.resolve("slow");
        long started = System.nanoTime();
        // Leave room for PNG decoding and rule detection on slower hosts, while
        // keeping the fake cell process longer than the shared page deadline.
        var error = assertThrows(ConversionFailureException.class, () -> fake("slow", Duration.ofSeconds(4))
                .recognizeLayoutResult(input, work, 1, PAGE, ParseLimits.defaults()));
        assertEquals("OCR_TIMEOUT", error.code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 6000);
        assertFalse(Files.exists(work.resolve("tesseract-grid-p1-c2.tsv")));
        try (var files = Files.list(work)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("tesseract-grid-") && p.toString().endsWith(".png"))); }
    }

    private static TextBlock nativeBlock(TextBlock b) {
        return new TextBlock(b.id(), b.pageNumber(), b.box(), b.text(), b.baselineY(), b.style(), b.zOrder());
    }
    private static PageModel nativePage(List<TextBlock> blocks) {
        return new PageModel(1, PAGE, blocks, List.of(), List.of(), List.of(), List.of(), List.of());
    }
    @Test void nativeFieldsFollowProvenRowsAndRetainLiteralSignedValues() {
        var grid = grid();
        var blocks = List.of(nativeBlock(in(grid, 3, "+00077.50%", .99)), nativeBlock(in(grid, 0, "Column A", .99)),
                nativeBlock(in(grid, 2, "-00127.50", .99)), nativeBlock(in(grid, 1, "Column B", .99)));
        assertEquals("Column A\tColumn B" + System.lineSeparator() + "-00127.50\t+00077.50%" + System.lineSeparator(),
                OcrRuledGrid.nativeText(grid, nativePage(blocks)));
        assertNull(OcrRuledGrid.nativeText(grid, nativePage(blocks.subList(1, 4))));
        var changed = new ArrayList<>(blocks); changed.set(0, in(grid, 3, "OCR word", .99));
        assertNull(OcrRuledGrid.nativeText(grid, nativePage(changed)));
        changed.set(0, nativeBlock(line("cross", "Spanning field", new Rect(35, 80, 240, 12), .99)));
        assertNull(OcrRuledGrid.nativeText(grid, nativePage(changed)));
    }
    @Test void nativeNumericFragmentsCannotSilentlyJoinAcrossAWhitespaceFreeBoundary() {
        var grid = grid(); var blocks = new ArrayList<>(List.of(nativeBlock(in(grid, 0, "A", .99)),
                nativeBlock(in(grid, 1, "B", .99)), nativeBlock(in(grid, 2, "127.50", .99))));
        var cell = grid.cells().get(3).box();
        blocks.add(nativeBlock(line("first", "12", new Rect(cell.x() + 10, cell.y() + 12, 10, 8), .99)));
        blocks.add(nativeBlock(line("second", "34", new Rect(cell.x() + 30, cell.y() + 12, 10, 8), .99)));
        assertNull(OcrRuledGrid.nativeText(grid, nativePage(blocks)));
        blocks.set(3, nativeBlock(line("first", "12 ", new Rect(cell.x() + 10, cell.y() + 12, 10, 8), .99)));
        assertTrue(OcrRuledGrid.nativeText(grid, nativePage(blocks)).endsWith("127.50\t12 34" + System.lineSeparator()));
    }
}
