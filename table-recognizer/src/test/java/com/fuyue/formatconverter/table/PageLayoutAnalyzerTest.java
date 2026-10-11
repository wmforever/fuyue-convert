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

    @Test void recognizesRepeatedAlignedTextAsBorderlessTable() {
        List<TextBlock> blocks = List.of(
                text("h1", 20, 20, 20, "Name", 25), text("h2", 80, 20, 18, "Amount", 25),
                text("r1c1", 20, 35, 24, "Alice", 40), text("r1c2", 80, 35, 18, "12.5", 40),
                text("r2c1", 20, 50, 22, "Bob", 55), text("r2c2", 80, 50, 18, "9.0", 55),
                text("r3c1", 20, 65, 26, "Carol", 70), text("r3c2", 80, 65, 18, "7.5", 70));

        PageModel analyzed = new PageLayoutAnalyzer().analyze(new PageModel(1, new Rect(0, 0, 210, 297),
                blocks, List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(1, analyzed.tables().size());
        TableModel table = analyzed.tables().get(0);
        assertEquals(4, table.rowCount());
        assertEquals(2, table.columnCount());
        assertEquals(8, table.cells().size());
        assertEquals(0, analyzed.paragraphs().size());
    }

    @Test void keepsWideTwoColumnBodyTextOutOfBorderlessTables() {
        List<TextBlock> blocks = List.of(
                text("left-1", 15, 20, 92, "正文左栏第一段包含较长的连续说明文字", 25),
                text("right-1", 125, 20, 70, "正文右栏第一段包含较长的连续说明文字", 25),
                text("left-2", 15, 35, 92, "正文左栏第二段包含较长的连续说明文字", 40),
                text("right-2", 125, 35, 70, "正文右栏第二段包含较长的连续说明文字", 40),
                text("left-3", 15, 50, 92, "正文左栏第三段包含较长的连续说明文字", 55),
                text("right-3", 125, 50, 70, "正文右栏第三段包含较长的连续说明文字", 55),
                text("left-4", 15, 65, 92, "正文左栏第四段包含较长的连续说明文字", 70),
                text("right-4", 125, 65, 70, "正文右栏第四段包含较长的连续说明文字", 70));

        PageModel analyzed = new PageLayoutAnalyzer().analyze(new PageModel(1, new Rect(0, 0, 210, 297),
                blocks, List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(0, analyzed.tables().size());
        assertEquals(blocks.size(), analyzed.paragraphs().size());
    }

    @Test void keepsWideEnglishBodyTextOutOfBorderlessTables() {
        List<TextBlock> blocks = List.of(
                text("left-1", 15, 20, 92, "The left column contains a long explanatory sentence", 25),
                text("right-1", 125, 20, 70, "The right column contains a long explanatory sentence", 25),
                text("left-2", 15, 35, 92, "Another paragraph continues the explanation in prose", 40),
                text("right-2", 125, 35, 70, "Another paragraph continues the explanation in prose", 40),
                text("left-3", 15, 50, 92, "Further details are described across this body text", 55),
                text("right-3", 125, 50, 70, "Further details are described across this body text", 55));

        PageModel analyzed = new PageLayoutAnalyzer().analyze(new PageModel(1, new Rect(0, 0, 210, 297),
                blocks, List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(0, analyzed.tables().size());
    }

    @Test void recognizesWideShortTextTableWithoutNumericValues() {
        List<TextBlock> blocks = List.of(
                text("h1", 15, 20, 18, "姓名", 25), text("h2", 125, 20, 18, "部门", 25),
                text("r1c1", 15, 35, 18, "张三", 40), text("r1c2", 125, 35, 18, "研发", 40),
                text("r2c1", 15, 50, 18, "李四", 55), text("r2c2", 125, 50, 18, "销售", 55),
                text("r3c1", 15, 65, 18, "王五", 70), text("r3c2", 125, 65, 18, "财务", 70));

        PageModel analyzed = new PageLayoutAnalyzer().analyze(new PageModel(1, new Rect(0, 0, 210, 297),
                blocks, List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(1, analyzed.tables().size());
        assertEquals(4, analyzed.tables().get(0).rowCount());
    }

    @Test void expandsOcrWordsInsideLineBlocksIntoSeparateTableColumns() {
        List<TextBlock> blocks = List.of(
                ocrLine("Name Amount", 20, 20, 80, 5, 25,
                        word("Name", 20, 20, 20), word("Amount", 80, 20, 20)),
                ocrLine("Alice 12.5", 20, 35, 80, 5, 40,
                        word("Alice", 20, 35, 25), word("12.5", 80, 35, 18)),
                ocrLine("Bob 9.0", 20, 50, 80, 5, 55,
                        word("Bob", 20, 50, 18), word("9.0", 80, 50, 18)),
                ocrLine("Carol 7.5", 20, 65, 80, 5, 70,
                        word("Carol", 20, 65, 28), word("7.5", 80, 65, 18)));

        PageModel analyzed = new PageLayoutAnalyzer().analyze(new PageModel(1, new Rect(0, 0, 210, 297),
                blocks, List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(1, analyzed.tables().size());
        TableModel table = analyzed.tables().get(0);
        assertEquals(4, table.rowCount());
        assertEquals(2, table.columnCount());
        assertEquals(List.of("Name", "Amount", "Alice", "12.5", "Bob", "9.0", "Carol", "7.5"),
                table.cells().stream().flatMap(cell -> cell.paragraphs().stream())
                        .flatMap(paragraph -> paragraph.runs().stream()).map(TextBlock::text).toList());
    }

    @Test void usesOcrWordLeftEdgesWhenCellWidthsMoveTextCentres() {
        List<TextBlock> blocks = List.of(
                ocrLine("Long heading Value", 20, 20, 110, 5, 25,
                        word("Long", 20, 20, 60), word("heading", 83, 20, 42), word("Value", 150, 20, 25)),
                ocrLine("A 12", 20, 35, 155, 5, 40,
                        word("A", 20, 35, 7), word("12", 150, 35, 15)),
                ocrLine("Longer 9", 20, 50, 155, 5, 55,
                        word("Longer", 20, 50, 55), word("9", 150, 50, 8)),
                ocrLine("Medium 7", 20, 65, 155, 5, 70,
                        word("Medium", 20, 65, 45), word("7", 150, 65, 8)));

        PageModel analyzed = new PageLayoutAnalyzer().analyze(new PageModel(1, new Rect(0, 0, 240, 297),
                blocks, List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(1, analyzed.tables().size());
        TableModel table = analyzed.tables().get(0);
        assertEquals(2, table.columnCount());
        assertEquals("Long heading", table.cells().get(0).paragraphs().get(0).runs().get(0).text());
        assertEquals(1, table.cells().get(0).columnSpan());
    }

    private TextBlock ocrLine(String line, double x, double y, double width, double height,
                              double baseline, TextBlock.OcrWord... words) {
        return new TextBlock("ocr-" + line, 1, new Rect(x, y, width, height), line, baseline,
                FontStyle.defaults(), 0, 0, 0, List.of(), Transform2D.IDENTITY, List.of(words));
    }

    private TextBlock.OcrWord word(String value, double x, double y, double width) {
        return new TextBlock.OcrWord(new Rect(x, y, width, 5), value, .98);
    }

    private TextBlock text(String id, double x, double y, double width, String value, double baseline) {
        return new TextBlock(id, 1, new Rect(x, y, width, 5.64), value, baseline,
                new FontStyle("KaiTi", 16, false, false, ColorValue.BLACK), 0);
    }
}
