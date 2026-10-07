package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OcrTableLineRecoveryTest {
    private static final Rect PAGE = new Rect(10, 20, 600, 400);
    private static TextBlock line(String text, Rect box, double confidence) {
        return new TextBlock(text, 1, box, text, box.bottom(), FontStyle.defaults(), 1,
                0, 0, List.of(), Transform2D.IDENTITY, List.of(new TextBlock.OcrWord(box, text, confidence)));
    }
    private static TesseractOcrConverter.RecognitionResult result(List<TextBlock> blocks) {
        var words = blocks.stream().flatMap(b -> b.ocrWords().stream()).toList();
        return new TesseractOcrConverter.RecognitionResult(blocks,
                words.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0), words.size());
    }
    private static TesseractOcrConverter.RecognitionResult source(String numeric) {
        List<TextBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 10; i++) blocks.add(line("BODY", new Rect(30 + i * 45, 40, 30, 10), .98));
        blocks.add(line("ORR", new Rect(35, 125, 540, 30), .1));
        blocks.add(line(numeric, new Rect(170, 230, 25, 10), .99));
        return result(blocks);
    }
    private static BufferedImage image() {
        var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 600, 400);
        g.setColor(Color.BLACK);
        for (int y : new int[]{100, 150, 250}) g.fillRect(20, y, 560, 2);
        for (int x : new int[]{20, 150, 300, 578}) g.fillRect(x, 100, 2, 152);
        g.fillRect(50, 120, 8, 12); g.dispose(); return image;
    }
    @Test void cropsOnlyProvenLocalGridAndLeavesEverySourcePixelUnchanged() {
        var image = image(); var before = image.getRGB(0, 0, 600, 400, null, 0, 600);
        var regions = OcrTableLineRecovery.prepare(image, PAGE, source("007"), Long.MAX_VALUE);
        assertEquals(1, regions.size()); var r = regions.get(0);
        assertEquals(r.x() + 10, r.box().x()); assertEquals(r.y() + 20, r.box().y());
        assertEquals(Color.BLACK.getRGB(), r.cleaned().getRGB(50 - r.x(), 120 - r.y()));
        assertEquals(Color.WHITE.getRGB(), r.cleaned().getRGB(150 - r.x(), 125 - r.y()));
        assertArrayEquals(before, image.getRGB(0, 0, 600, 400, null, 0, 600));
    }
    @Test void expiredBlankBrokenAndColouredRegionsAreRejected() {
        assertTrue(OcrTableLineRecovery.prepare(image(), PAGE, source("007"), 0).isEmpty());
        var blank = image(); var g = blank.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 600, 400); g.dispose();
        assertTrue(OcrTableLineRecovery.prepare(blank, PAGE, source("007"), Long.MAX_VALUE).isEmpty());
        var stamp = image(); stamp.setRGB(70, 120, Color.RED.getRGB());
        assertTrue(OcrTableLineRecovery.prepare(stamp, PAGE, source("007"), Long.MAX_VALUE).isEmpty());
        var broken = image(); var bg = broken.createGraphics(); bg.setColor(Color.WHITE);
        bg.fillRect(280, 98, 10, 7); bg.dispose();
        assertTrue(OcrTableLineRecovery.prepare(broken, PAGE, source("007"), Long.MAX_VALUE).isEmpty());
    }
    @Test void outsideWordsStayIdenticalAndLiteralNumericChangesAreRejected() {
        var old = source("007");
        var r = new OcrTableLineRecovery.Region(20, 100, 560, 152,
                new Rect(30, 120, 560, 152), image());
        var good = result(List.of(line("Column", new Rect(40, 125, 80, 10), .99),
                line("007", new Rect(170, 230, 25, 10), .99)));
        var selected = OcrTableLineRecovery.select(r, old, good);
        assertTrue(selected.tableLineRecovery());
        for (int i = 0; i < 10; i++) assertSame(old.blocks().get(i).ocrWords().get(0), selected.blocks().get(i).ocrWords().get(0));
        assertTrue(selected.conflicts().get(0).contains("ORR"));
        for (String value : List.of("7", "-007", "007%", "007 1")) {
            var changed = result(List.of(line("Column", new Rect(40, 125, 80, 10), .99),
                    line(value, new Rect(170, 230, 25, 10), .99)));
            assertSame(old, OcrTableLineRecovery.select(r, old, changed));
        }
    }
}
