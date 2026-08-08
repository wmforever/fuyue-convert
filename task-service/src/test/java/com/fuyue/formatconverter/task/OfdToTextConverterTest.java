package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ParagraphModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfdToTextConverterTest {
    @Test
    void includesTableAndFloatingTextWhenParagraphsOnlyContainBodyText() {
        TextBlock title = text("title", 10, 10, "发票标题");
        TextBlock tableLabel = text("table-label", 10, 20, "购买方");
        TextBlock tableValue = text("table-value", 40, 20, "测试公司");
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297),
                List.of(title, tableValue, tableLabel), List.of(), List.of(),
                List.of(new ParagraphModel(title.box(), List.of(title), ParagraphModel.Alignment.LEFT, 0)),
                List.of(), List.of());

        assertEquals("发票标题\n购买方测试公司\n", normalizeLines(OfdToTextConverter.text(List.of(page))));
    }

    private TextBlock text(String id, double x, double baseline, String value) {
        return new TextBlock(id, 1, new Rect(x, baseline - 4, 20, 5), value, baseline,
                new FontStyle("SimSun", 10, false, false, ColorValue.BLACK), 0);
    }

    private String normalizeLines(String value) {
        return value.replace("\r\n", "\n");
    }
}
