package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OcrPartialRecoveryTest {
    @TempDir Path temp;

    @Test void deskewKeepsExactOriginalWholeLineAndAdmitsOnlyDisjointRecoveredLine() throws Exception {
        Rect left = new Rect(10, 10, 12, 10), right = new Rect(30, 10, 12, 10);
        var protectedLine = line("source", "１２ ３４", List.of(
                new TextBlock.OcrWord(left, "１２", .96), new TextBlock.OcrWord(right, "３４", .96)));
        var original = result(protectedLine);
        var fused = line("candidate", "１２３４", List.of(new TextBlock.OcrWord(left.union(right), "１２３４", .98)));
        var recovered = wordLine("new", "Recovered independent prose", new Rect(10, 60, 80, 10));
        try (var prepared = prepared()) {
            var selected = OcrDeskewSelection.select(original, result(fused, recovered), prepared,
                    new Rect(0, 0, 200, 200), 200, 200, .35);
            assertEquals(2, selected.blocks().size());
            assertSame(protectedLine, selected.blocks().get(0));
            assertEquals("１２ ３４", selected.blocks().get(0).text());
            assertEquals(protectedLine.ocrWords(), selected.blocks().get(0).ocrWords());
            assertEquals(recovered.text(), selected.blocks().get(1).text());
            assertFalse(selected.conflicts().isEmpty());
            assertTrue(selected.possibleTextOmission());
            assertTrue(selected.partialRecovery());
        }
    }

    @Test void enhancementKeepsOriginalNumericLineGeometryAndAddsDisjointLineWithWarning() throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(new Color(160, 160, 160));
        graphics.fillRect(0, 0, 200, 200); graphics.setColor(new Color(130, 130, 130));
        graphics.fillRect(20, 80, 140, 12); graphics.dispose();
        Path source = temp.resolve("source.png"); ImageIO.write(image, "png", source.toFile()); image.flush();
        Path engine = temp.resolve("fake");
        Files.writeString(engine, "#!/bin/sh\nbase=\"$2\"\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n"
                + "case \"$1\" in *tesseract-enhanced-*)\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t10\\t10\\t60\\t10\\t98\\t0.95 corrected\\n5\\t1\\t1\\t1\\t2\\t1\\t10\\t60\\t80\\t10\\t98\\tRecovered independent prose\\n' >> \"${base}.tsv\"\n;; *)\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t10\\t10\\t30\\t10\\t96\\t.95\\n5\\t1\\t1\\t1\\t1\\t2\\t50\\t10\\t20\\t10\\t20\\tfaint\\n' >> \"${base}.tsv\"\n;; esac\n");
        assertTrue(engine.toFile().setExecutable(true));
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, new TesseractOcrConverter.Settings(
                engine, "eng", "fake", Duration.ofSeconds(5), 1, .35, .75, 25_000_000, temp.resolve("locks")));
        var selected = converter.recognizeLayoutResult(source, temp.resolve("work"), 1,
                new Rect(0, 0, 200, 200), ParseLimits.defaults());
        assertTrue(selected.imageEnhanced());
        assertTrue(selected.partialRecovery());
        assertEquals(List.of(".95 faint", "Recovered independent prose"), selected.blocks().stream().map(TextBlock::text).toList());
        assertEquals(new Rect(10, 10, 30, 10), selected.blocks().get(0).ocrWords().get(0).box());
        assertTrue(selected.possibleTextOmission());
        assertTrue(converter.warningsFor(selected, 1, "image").stream().anyMatch(w -> w.code() == WarningCode.OCR_RECOGNITION_CONFLICT));
        assertTrue(converter.warningsFor(selected, 1, "image").stream().filter(w -> w.code() == WarningCode.OCR_POSSIBLE_TEXT_OMISSION)
                .allMatch(w -> w.message().contains("严格分离的新行") && !w.message().contains("阴影字迹覆盖探测")));
    }

    static OcrDeskew.Prepared prepared() {
        return new OcrDeskew.Prepared(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), 6, new AffineTransform());
    }

    @Test void preservesIndependentDecimalDateIdAndNeighborChangesWithoutInferringTheirMeaning() {
        String[][] pairs = {{".95", "0.95"}, {"-.95", "-0.95"}, {"20 26", "2026"},
                {"-17", "2027-04-17"}, {"ID00424", "ID80424"}, {"１２ ３４", "１２３４"},
                {"USD . 95", "USD0.95"}, {"95 %", "95"}, {"( .95 )", ".95"},
                {"1\u202f234.50", "1234.50"}, {"Amount:－．９５", "Amount:-0.95"}};
        for (var pair : pairs) {
            Rect box = new Rect(10, 10, 80, 10);
            var source = wordLine("source", pair[0] + " faint neighbor", box);
            var original = result(source);
            var candidate = result(wordLine("changed", pair[1] + " changed neighbor", box),
                    wordLine("new", "An independent recovered sentence with ID 00781", new Rect(10, 60, 80, 10)));
            var selected = OcrPartialRecovery.select(original, candidate, new Rect(0, 0, 200, 200), .35,
                    -.05, false, 6, Long.MAX_VALUE);
            assertEquals(2, selected.blocks().size(), pair[0]);
            assertSame(source, selected.blocks().get(0));
            assertEquals(pair[0] + " faint neighbor", selected.blocks().get(0).text());
            assertEquals(source.ocrWords(), selected.blocks().get(0).ocrWords());
            assertTrue(selected.possibleTextOmission());
        }
    }

    @Test void rejectsSpanningInflatedManyToOneTablesColumnsAndNearBoundaryGeometry() {
        var source = wordLine("source", "USD .95", new Rect(10, 10, 80, 10));
        var original = result(source);
        var normal = wordLine("changed", "USD0.95", new Rect(10, 10, 80, 10));
        var disjoint = wordLine("new", "Independent recovered sentence", new Rect(10, 60, 80, 10));
        for (var candidate : List.of(
                result(normal, wordLine("new", "side column independent text", new Rect(120, 10, 70, 10))),
                result(normal, wordLine("new", "near boundary independent text", new Rect(10, 21, 80, 10))),
                result(normal, wordLine("new", "offset column independent text", new Rect(150, 60, 40, 10))),
                result(wordLine("span", "USD0.95 spanning recovered rows", new Rect(10, 10, 80, 80)), disjoint))) {
            assertSame(original, OcrPartialRecovery.select(original, candidate, new Rect(0, 0, 200, 200),
                    .35, -.05, false, 6, Long.MAX_VALUE));
        }
        var inflated = result(wordLine("source", "USD .95", new Rect(10, 10, 80, 170)));
        assertSame(inflated, OcrPartialRecovery.select(inflated, result(normal, disjoint),
                new Rect(0, 0, 200, 200), .35, -.05, false, 6, Long.MAX_VALUE));
        var twoSources = result(wordLine("source1", "12", new Rect(10, 10, 10, 10)),
                wordLine("source2", "34", new Rect(50, 10, 10, 10)));
        assertSame(twoSources, OcrPartialRecovery.select(twoSources, result(normal, disjoint),
                new Rect(0, 0, 200, 200), .35, -.05, false, 6, Long.MAX_VALUE));
    }

    @Test void rejectsBlankTimeoutLowGainAndUntrustedNewLine() {
        var source = wordLine("source", "ID 00424", new Rect(10, 10, 80, 10));
        var original = result(source);
        var candidate = result(wordLine("changed", "ID80424", new Rect(10, 10, 80, 10)),
                wordLine("new", "Independent recovered sentence", new Rect(10, 60, 80, 10)));
        assertSame(original, OcrPartialRecovery.select(original, candidate, new Rect(0, 0, 200, 200), .35,
                -.05, false, 6, 0));
        assertSame(original, OcrPartialRecovery.select(original, candidate, new Rect(0, 0, 200, 200), .35,
                .05, true, 0, Long.MAX_VALUE));
        assertSame(original, OcrPartialRecovery.select(original, result(), new Rect(0, 0, 200, 200), .35,
                -.05, false, 6, Long.MAX_VALUE));
        var blank = result();
        assertSame(blank, OcrPartialRecovery.select(blank, candidate, new Rect(0, 0, 200, 200), .35,
                -.05, false, 6, Long.MAX_VALUE));
        var uncertain = line("new", "Independent recovered sentence", List.of(
                new TextBlock.OcrWord(new Rect(10, 60, 80, 10), "Independent recovered sentence", .20)));
        assertSame(original, OcrPartialRecovery.select(original, result(candidate.blocks().get(0), uncertain),
                new Rect(0, 0, 200, 200), .35, -.05, false, 6, Long.MAX_VALUE));
    }

    @Test void rejectsTableGutterEvenWhenASpanningProseLineFillsTheGlobalGap() {
        var source = wordLine("source", "USD .95", new Rect(10, 10, 30, 10));
        var original = result(source);
        var tableRow = line("table", "0.95 right table cell", List.of(
                new TextBlock.OcrWord(new Rect(10, 10, 30, 10), "0.95", .98),
                new TextBlock.OcrWord(new Rect(120, 10, 70, 10), "right table cell", .98)));
        var candidate = result(tableRow, wordLine("new", "Spanning independent prose", new Rect(10, 60, 180, 10)));
        assertSame(original, OcrPartialRecovery.select(original, candidate, new Rect(0, 0, 200, 200),
                .35, -.05, false, 6, Long.MAX_VALUE));
    }

    @Test void recoveredWordLinesSortBetweenImmutableOriginalZOrders() {
        var first = withZ(wordLine("source1", "Source ID 00424", new Rect(10, 10, 80, 10)), 1);
        var last = withZ(wordLine("source2", "Last source ID 00817", new Rect(10, 160, 80, 10)), 2);
        var candidate = result(withZ(wordLine("changed1", "Changed ID80424", first.box()), 1),
                withZ(wordLine("new1", "First independent recovered sentence", new Rect(10, 60, 80, 10)), 2),
                withZ(wordLine("new2", "Second independent recovered sentence", new Rect(10, 110, 80, 10)), 3),
                withZ(wordLine("changed2", "Changed ID90817", last.box()), 4));
        var selected = OcrPartialRecovery.select(result(first, last), candidate, new Rect(0, 0, 200, 200),
                .35, -.05, false, 6, Long.MAX_VALUE);
        assertEquals(4, selected.blocks().size());
        assertSame(first, selected.blocks().get(0)); assertSame(last, selected.blocks().get(3));
        assertEquals(selected.blocks(), selected.blocks().stream()
                .sorted(java.util.Comparator.comparingInt(TextBlock::zOrder)).toList(),
                "Word renderer sorts by zOrder; new lines must not pass an original line");
        assertEquals(1, first.zOrder()); assertEquals(2, last.zOrder());
    }

    private static TextBlock withZ(TextBlock block, int z) {
        return new TextBlock(block.id(), block.pageNumber(), block.box(), block.text(), block.baselineY(),
                block.style(), z, block.textOffsetXmm(), block.textOffsetYmm(), block.advancesMm(), block.transform(), block.ocrWords());
    }

    static TextBlock wordLine(String id, String text, Rect box) {
        return line(id, text, List.of(new TextBlock.OcrWord(box, text, .98)));
    }

    static TextBlock line(String id, String text, List<TextBlock.OcrWord> words) {
        Rect box = words.get(0).box();
        for (var word : words) box = box.union(word.box());
        return new TextBlock(id, 1, box, text, box.bottom(), null, 0, 0, 0, List.of(), Transform2D.IDENTITY, words);
    }

    static TesseractOcrConverter.RecognitionResult result(TextBlock... blocks) {
        var words = java.util.Arrays.stream(blocks).flatMap(b -> b.ocrWords().stream()).toList();
        return new TesseractOcrConverter.RecognitionResult(List.of(blocks),
                words.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0), words.size());
    }
}
