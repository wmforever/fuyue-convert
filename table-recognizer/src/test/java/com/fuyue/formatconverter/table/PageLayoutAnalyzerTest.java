package com.fuyue.formatconverter.table;

import com.fuyue.formatconverter.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageLayoutAnalyzerTest {
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
