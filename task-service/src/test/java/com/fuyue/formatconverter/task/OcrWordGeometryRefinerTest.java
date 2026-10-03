package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OcrWordGeometryRefinerTest {
    private static final Rect PAGE = new Rect(0, 0, 160, 30);

    @Test void alignsRegularChineseWordsToInkWithoutChangingSourcePixels() {
        BufferedImage pixels = source();
        int[] before = pixels.getRGB(0, 0, 160, 30, null, 0, 160);
        TextBlock original = line("本合同服务须履行", List.of("本合同", "服务", "须履行"));
        TextBlock corrected = OcrWordGeometryRefiner.refine(original, pixels, PAGE);
        assertEquals(original.text(), corrected.text());
        assertEquals(new Rect(2, 5, 56, 18), corrected.ocrWords().get(0).box());
        assertEquals(new Rect(62, 5, 36, 18), corrected.ocrWords().get(1).box());
        assertEquals(new Rect(102, 5, 56, 18), corrected.ocrWords().get(2).box());
        assertArrayEquals(before, pixels.getRGB(0, 0, 160, 30, null, 0, 160));
        pixels.flush();
    }

    @Test void leavesIncompleteRecognitionAndAmbiguousCellGapsUnchanged() {
        BufferedImage pixels = source();
        TextBlock missing = line("本合同服须履行", List.of("本合同", "服", "须履行"));
        assertSame(missing, OcrWordGeometryRefiner.refine(missing, pixels, PAGE));
        for (int y = 5; y < 23; y++) pixels.setRGB(60, y, Color.BLACK.getRGB());
        TextBlock complete = line("本合同服务须履行", List.of("本合同", "服务", "须履行"));
        assertSame(complete, OcrWordGeometryRefiner.refine(complete, pixels, PAGE),
                "无法确认字间白色隔断时，不猜测覆盖其中未识别的标记");
        pixels.flush();
    }

    @Test void treatsTransparentBlackAsWhiteBackground() {
        BufferedImage opaque = source();
        BufferedImage pixels = new BufferedImage(160, 30, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0, 0, 160, 30, opaque.getRGB(0, 0, 160, 30, null, 0, 160), 0, 160);
        for (int y = 5; y < 23; y++) pixels.setRGB(60, y, 0x00000000);
        TextBlock original = line("本合同服务须履行", List.of("本合同", "服务", "须履行"));
        assertEquals(new Rect(2, 5, 56, 18), OcrWordGeometryRefiner.refine(original, pixels, PAGE)
                .ocrWords().get(0).box());
        opaque.flush(); pixels.flush();
    }

    private TextBlock line(String text, List<String> words) {
        List<TextBlock.OcrWord> geometry = List.of(
                new TextBlock.OcrWord(new Rect(2, 5, 36, 18), words.get(0), .95),
                new TextBlock.OcrWord(new Rect(76, 5, 15, 18), words.get(1), .95),
                new TextBlock.OcrWord(new Rect(133, 5, 25, 18), words.get(2), .95));
        return new TextBlock("ocr", 1, new Rect(2, 5, 156, 18), text, 23,
                new FontStyle("Arial", 12, false, false, null), 1, 0, 0, List.of(), Transform2D.IDENTITY, geometry);
    }

    private BufferedImage source() {
        BufferedImage image = new BufferedImage(160, 30, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 160, 30); g.setColor(Color.BLACK);
        for (int index = 0; index < 8; index++) g.fillRect(2 + index * 20, 5, 16, 18);
        g.dispose(); return image;
    }
}
