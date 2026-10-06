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

    @Test void refinesNumberedChineseHeadingButPreservesNumberObjectAndUnknownInk() {
        BufferedImage pixels = new BufferedImage(180, 30, BufferedImage.TYPE_INT_RGB);
        var g = pixels.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 180, 30);
        BufferedImage chinese = source(); g.drawImage(chinese, 20, 0, null); chinese.flush();
        g.setColor(Color.BLACK); g.fillRect(2, 9, 6, 10); g.dispose();
        TextBlock source = line("本合同服务须履行", List.of("本合同", "服务", "须履行"));
        var number = new TextBlock.OcrWord(new Rect(2, 9, 6, 10), "04.01", .97);
        var words = new java.util.ArrayList<TextBlock.OcrWord>(); words.add(number);
        for (var word : source.ocrWords()) words.add(new TextBlock.OcrWord(
                new Rect(word.box().x() + 20, word.box().y(), word.box().width(), word.box().height()),
                word.text(), word.confidence()));
        Rect box = words.stream().map(TextBlock.OcrWord::box).reduce(Rect::union).orElseThrow();
        TextBlock original = new TextBlock("heading", 1, box, "04.01 本合同服务须履行", 23,
                source.style(), 1, 0, 0, List.of(), Transform2D.IDENTITY, words);
        int[] before = pixels.getRGB(0, 0, 180, 30, null, 0, 180);
        TextBlock refined = OcrWordGeometryRefiner.refine(original, pixels, new Rect(0, 0, 180, 30));
        assertNotSame(original, refined); assertSame(number, refined.ocrWords().get(0));
        assertEquals(original.text(), refined.text());
        assertEquals(new Rect(22, 5, 56, 18), refined.ocrWords().get(1).box());
        assertArrayEquals(before, pixels.getRGB(0, 0, 180, 30, null, 0, 180));
        var quotedWords = new java.util.ArrayList<>(words);
        var first = words.get(1);
        quotedWords.set(1, new TextBlock.OcrWord(first.box(), "”" + first.text(), .45));
        TextBlock quoted = new TextBlock(original.id(), 1, box, "04.01 ”本合同服务须履行", 23,
                source.style(), 1, 0, 0, List.of(), Transform2D.IDENTITY, quotedWords);
        TextBlock fitted = OcrWordGeometryRefiner.refine(quoted, pixels, new Rect(0, 0, 180, 30));
        assertNotSame(quoted, fitted); assertSame(number, fitted.ocrWords().get(0));
        assertEquals(quoted.text(), fitted.text()); assertEquals("”本合同", fitted.ocrWords().get(1).text());
        assertEquals(.45, fitted.ocrWords().get(1).confidence());
        assertEquals(new Rect(22, 5, 56, 18), fitted.ocrWords().get(1).box());
        for (int y = 5; y < 23; y++) pixels.setRGB(80, y, Color.BLACK.getRGB());
        assertSame(original, OcrWordGeometryRefiner.refine(original, pixels, new Rect(0, 0, 180, 30)),
                "不能用标题前缀绕过未知印记和字间隔断的保护");
        pixels.flush();
    }

    @Test void restoresConnectedClippedHeadingInkButKeepsIndependentMarksAndNumericAnchors() {
        BufferedImage pixels = new BufferedImage(360, 60, BufferedImage.TYPE_INT_RGB);
        var g = pixels.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 360, 60);
        BufferedImage chinese = source(); g.drawImage(chinese, 20, 0, 320, 60, null); chinese.flush();
        g.setColor(Color.BLACK); g.fillRect(2, 18, 12, 20); g.dispose();
        var source = line("本合同服务须履行", List.of("本合同", "服务", "须履行"));
        var number = new TextBlock.OcrWord(new Rect(2, 18, 12, 20), "04.01", .97);
        var words = new java.util.ArrayList<TextBlock.OcrWord>(); words.add(number);
        for (int i = 0; i < source.ocrWords().size(); i++) {
            var word = source.ocrWords().get(i); double clipped = i == 0 ? 4 : 0;
            words.add(new TextBlock.OcrWord(new Rect(20 + word.box().x() * 2 + clipped,
                    word.box().y() * 2, word.box().width() * 2 - clipped, word.box().height() * 2),
                    word.text(), word.confidence()));
        }
        Rect box = words.stream().map(TextBlock.OcrWord::box).reduce(Rect::union).orElseThrow();
        TextBlock original = new TextBlock("heading", 1, box, "04.01 本合同服务须履行", 46,
                source.style(), 1, 0, 0, List.of(), Transform2D.IDENTITY, words);
        Rect page = new Rect(0, 0, 360, 60);
        int[] before = pixels.getRGB(0, 0, 360, 60, null, 0, 360);
        TextBlock refined = OcrWordGeometryRefiner.refine(original, pixels, page);
        assertEquals(new Rect(24, 10, 112, 36), refined.ocrWords().get(1).box());
        assertSame(number, refined.ocrWords().get(0)); assertEquals(original.text(), refined.text());
        assertEquals(source.ocrWords().get(0).confidence(), refined.ocrWords().get(1).confidence());
        assertArrayEquals(before, pixels.getRGB(0, 0, 360, 60, null, 0, 360));
        // An isolated dot inside the proposed extension must remain outside every revised word.
        pixels.setRGB(22, 12, Color.BLACK.getRGB());
        assertTrue(OcrWordGeometryRefiner.refine(original, pixels, page).ocrWords().get(1).box().x() >= 28);
        pixels.setRGB(22, 12, new Color(255, 180, 180).getRGB());
        assertTrue(OcrWordGeometryRefiner.refine(original, pixels, page).ocrWords().get(1).box().x() >= 28,
                "Bright coloured ink outside the old box must not be covered");
        pixels.setRGB(22, 12, Color.WHITE.getRGB());
        var near = new java.util.ArrayList<>(words);
        var crowdedNumber = new TextBlock.OcrWord(new Rect(2, 18, 25, 20), "04.01", .97);
        near.set(0, crowdedNumber);
        TextBlock crowded = new TextBlock(original.id(), 1, box, original.text(), 46,
                source.style(), 1, 0, 0, List.of(), Transform2D.IDENTITY, near);
        TextBlock guarded = OcrWordGeometryRefiner.refine(crowded, pixels, page);
        assertSame(crowdedNumber, guarded.ocrWords().get(0));
        assertTrue(guarded.ocrWords().get(1).box().x() >= 28);
        pixels.flush();
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
