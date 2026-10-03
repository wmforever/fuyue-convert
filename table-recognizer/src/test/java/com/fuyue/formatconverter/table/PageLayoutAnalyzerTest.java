package com.fuyue.formatconverter.table;

import com.fuyue.formatconverter.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageLayoutAnalyzerTest {
    @Test void separatesColumnsOnMatchingBaselinesWithoutSplittingNearbyRuns() {
        List<TextBlock> blocks = List.of(
                text("left-first", 15, 20, 18, "LEFT-", 24),
                text("left-suffix", 34, 20, 4, "1", 24),
                text("right-first", 125, 20, 25, "RIGHT-1", 24),
                text("left-second", 15, 35, 25, "LEFT-2", 39),
                text("right-second", 125, 35, 25, "RIGHT-2", 39));
        PageModel source = new PageModel(1, new Rect(0, 0, 210, 297), blocks,
                List.of(), List.of(), List.of(), List.of(), List.of());

        PageModel analyzed = new PageLayoutAnalyzer().analyze(source);

        assertEquals(4, analyzed.paragraphs().size());
        assertEquals(List.of("LEFT-", "1"), analyzed.paragraphs().get(0).runs().stream().map(TextBlock::text).toList());
        assertEquals(List.of(15d, 125d, 15d, 125d),
                analyzed.paragraphs().stream().map(paragraph -> paragraph.box().x()).toList());
        assertEquals(blocks.size(), analyzed.paragraphs().stream().mapToInt(paragraph -> paragraph.runs().size()).sum());
    }

    @Test void mergesTextObjectsOnTheSameBaselineIntoOneWordParagraph() {
        List<TextBlock> blocks = List.of(
                text("title-1", 69.55, 29.33, 34.57, "测试询价单（", 34.16),
                text("title-2", 103.42, 29.33, 13.24, "Word", 34.16),
                text("title-3", 116.12, 29.33, 22.01, "通用版）", 34.16),
                text("number", 30.90, 51.17, 6.11, "1.", 56.01),
                text("body", 36.53, 51.17, 139.50, "本次询价为内部测试使用", 56.01),
                text("next", 30.90, 62.14, 128.09, "系统录入测试", 66.97));
        PageModel source = new PageModel(1, new Rect(0, 0, 209.9, 297), blocks,
                List.of(), List.of(), List.of(), List.of(), List.of());

        PageModel analyzed = new PageLayoutAnalyzer().analyze(source);

        assertEquals(3, analyzed.paragraphs().size());
        assertEquals(List.of("测试询价单（", "Word", "通用版）"),
                analyzed.paragraphs().get(0).runs().stream().map(TextBlock::text).toList());
        assertEquals(ParagraphModel.Alignment.CENTER, analyzed.paragraphs().get(0).alignment());
        assertEquals(List.of("1.", "本次询价为内部测试使用"),
                analyzed.paragraphs().get(1).runs().stream().map(TextBlock::text).toList());
        assertEquals(ParagraphModel.Alignment.LEFT, analyzed.paragraphs().get(1).alignment());
    }

    private TextBlock text(String id, double x, double y, double width, String value, double baseline) {
        return new TextBlock(id, 1, new Rect(x, y, width, 5.64), value, baseline,
                new FontStyle("KaiTi", 16, false, false, ColorValue.BLACK), 0);
    }
}
