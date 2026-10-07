package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OcrCoverageProbeTest {
    @Test
    void missingShadedLinesTriggerButCompleteBoxesDoNotIncludingOffsetScaledCoordinates() {
        var image = page(true);
        Rect physical = new Rect(20, 30, 300, 200);
        assertTrue(probe(image, List.of(word(new Rect(25, 65, 20, 10))), physical));
        assertFalse(probe(image, List.of(word(physical)), physical));
        assertFalse(probe(image, List.of(word(new Rect(0, 0, 600, 400))), new Rect(0, 0, 600, 400)));
        image.flush();
    }

    @Test
    void blankNoiseSingleMarkUniformPaperAndDenseContentDoNotTrigger() {
        for (String kind : List.of("blank", "noise", "stamp", "uniform", "dense")) {
            var image = page(false);
            var graphics = image.createGraphics(); graphics.setColor(Color.BLACK);
            if (kind.equals("noise")) for (int i = 0; i < 30; i++) graphics.fillRect(10 + i * 15, 20 + i * 9, 1, 1);
            if (kind.equals("stamp")) graphics.fillRect(100, 170, 80, 20);
            if (kind.equals("uniform")) {
                graphics.setColor(new Color(160, 160, 160)); graphics.fillRect(0, 0, 600, 400);
                graphics.setColor(Color.BLACK); for (int y = 60; y < 350; y += 60) graphics.fillRect(60, y, 300, 10);
            }
            if (kind.equals("dense")) for (int y = 0; y < 400; y += 10) graphics.fillRect(0, y, 600, 7);
            graphics.dispose();
            assertFalse(probe(image, List.of(), new Rect(0, 0, 600, 400)), kind); image.flush();
        }
    }

    @Test
    void respectsDeadlineAspectAndCumulativeBoxWorkLimits() {
        var image = page(true); Rect physical = new Rect(0, 0, 600, 400);
        assertFalse(OcrCoverageProbe.hasUncoveredShadedInk(image, List.of(), physical, System.nanoTime() - 1));
        assertFalse(probe(image, java.util.Collections.nCopies(10, word(physical)), physical));
        assertFalse(probe(image, List.of(), new Rect(0, 0, 0, 400)));
        image.flush();
        var narrow = new BufferedImage(32769, 1, BufferedImage.TYPE_INT_RGB);
        assertFalse(probe(narrow, List.of(), new Rect(0, 0, 32769, 1))); narrow.flush();
    }

    private static boolean probe(BufferedImage image, List<TextBlock> blocks, Rect physical) {
        return OcrCoverageProbe.hasUncoveredShadedInk(image, blocks, physical, System.nanoTime() + 1_000_000_000L);
    }

    private static TextBlock word(Rect box) {
        return new TextBlock("word", 1, box, "seen", box.y() + box.height(), null, 0, 0, 0, List.of(),
                com.fuyue.formatconverter.model.Transform2D.IDENTITY, List.of(new TextBlock.OcrWord(box, "seen", .9)));
    }

    private static BufferedImage page(boolean ink) {
        var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) for (int x = 0; x < 600; x++) {
            int gray = 90 + x * 130 / 600;
            if (ink && y >= 60 && y < 350 && y % 60 < 10 && x > 60 && x < 450 && x % 20 < 12) gray = 0;
            image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
        }
        return image;
    }
}
