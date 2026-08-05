package cn.tensafe.ofd2word.docx;

import cn.tensafe.ofd2word.model.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STSectionMark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PoiDocxRendererTest {
    @TempDir Path temp;

    @Test void writesEditableTextAndMergedTable() throws Exception {
        TextBlock title = text("title", 10, 10, "测试标题");
        ParagraphModel titleParagraph = new ParagraphModel(title.box(), List.of(title), ParagraphModel.Alignment.CENTER, 0);
        BorderStyle border = BorderStyle.solid(0.2, ColorValue.BLACK);
        CellModel merged = new CellModel(0, 0, 1, 2, new Rect(10, 30, 100, 20),
                List.of(new ParagraphModel(new Rect(10, 30, 100, 20), List.of(text("m", 12, 33, "合并表头")), ParagraphModel.Alignment.CENTER, 0)),
                border, border, border, border, null, ParagraphModel.Alignment.CENTER, CellModel.VerticalAlignment.CENTER);
        CellModel left = cell(1, 0, "A", border);
        CellModel right = cell(1, 1, "B", border);
        TableModel table = new TableModel("t", 1, new Rect(10, 30, 100, 40), List.of(10d, 60d, 110d),
                List.of(30d, 50d, 70d), List.of(merged, left, right),
                List.of(new MergeCellModel(0, 0, 1, 2, 1)), 1, List.of());
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(), List.of(), List.of(),
                List.of(titleParagraph), List.of(table), List.of());
        Path output = temp.resolve("result.docx");
        new PoiDocxRenderer().render(new DocumentModel("test.ofd", "test", 1, List.of(page), List.of()), output);

        assertTrue(Files.size(output) > 0);
        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String xml = reopened.getDocument().xmlText();
            assertTrue(xml.contains("测试标题"));
            assertTrue(xml.contains("合并表头"));
            assertTrue(xml.contains("gridSpan"));
            assertFalse(xml.contains("txbxContent"));
            assertEquals(1, reopened.getTables().size(), "规则表格应是正文中的真实 Word 表格");
        }
    }

    @Test void writesVerticalMergeMarkers() throws Exception {
        BorderStyle border = BorderStyle.solid(0.2, ColorValue.BLACK);
        Rect mergedBox = new Rect(10, 20, 50, 40);
        CellModel merged = new CellModel(0, 0, 2, 1, mergedBox,
                List.of(new ParagraphModel(mergedBox, List.of(text("vm", 12, 25, "纵向合并")), ParagraphModel.Alignment.CENTER, 0)),
                border, border, border, border, null, ParagraphModel.Alignment.CENTER, CellModel.VerticalAlignment.CENTER);
        CellModel topRight = new CellModel(0, 1, 1, 1, new Rect(60, 20, 50, 20), List.of(), border, border, border, border,
                null, ParagraphModel.Alignment.LEFT, CellModel.VerticalAlignment.CENTER);
        CellModel bottomRight = new CellModel(1, 1, 1, 1, new Rect(60, 40, 50, 20), List.of(), border, border, border, border,
                null, ParagraphModel.Alignment.LEFT, CellModel.VerticalAlignment.CENTER);
        TableModel table = new TableModel("vertical", 1, new Rect(10, 20, 100, 40), List.of(10d, 60d, 110d),
                List.of(20d, 40d, 60d), List.of(merged, topRight, bottomRight),
                List.of(new MergeCellModel(0, 0, 2, 1, 1)), 1, List.of());
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(), List.of(), List.of(), List.of(), List.of(table), List.of());
        Path output = temp.resolve("vertical.docx");
        new PoiDocxRenderer().render(new DocumentModel("test.ofd", "test", 1, List.of(page), List.of()), output);
        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String xml = reopened.getDocument().xmlText();
            assertTrue(xml.contains("restart"));
            assertTrue(xml.contains("continue"));
        }
    }

    @Test void rendersEveryPageWithIndependentSectionSize() throws Exception {
        PageModel portrait = pageWithText(1, new Rect(0, 0, 210, 297), "第一页正文");
        PageModel landscape = pageWithText(2, new Rect(0, 0, 297, 210), "第二页正文");
        Path output = temp.resolve("multi-page.docx");

        new PoiDocxRenderer().render(new DocumentModel("multi.ofd", "test", 2,
                List.of(portrait, landscape), List.of()), output);

        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String allText = reopened.getDocument().xmlText();
            assertTrue(allText.contains("第一页正文"));
            assertTrue(allText.contains("第二页正文"));
            var sectionBreaks = reopened.getParagraphs().stream()
                    .filter(p -> p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr())
                    .toList();
            assertEquals(1, sectionBreaks.size());
            assertEquals(STSectionMark.NEXT_PAGE,
                    sectionBreaks.get(0).getCTP().getPPr().getSectPr().getType().getVal());
            assertEquals(11906, Integer.parseInt(sectionBreaks.get(0).getCTP().getPPr().getSectPr().getPgSz().getW().toString()));
            assertEquals(STPageOrientation.LANDSCAPE,
                    reopened.getDocument().getBody().getSectPr().getPgSz().getOrient());
            assertEquals(16838, Integer.parseInt(reopened.getDocument().getBody().getSectPr().getPgSz().getW().toString()));
        }
    }

    @Test void semanticLayoutKeepsEditableTextCharacterSpacingAndLineColorWithoutTextBoxes() throws Exception {
        FontStyle titleStyle = new FontStyle("方正小标宋简体", 48, false, false,
                new ColorValue(250, 64, 6, 255));
        TextBlock title = new TextBlock("title", 1, new Rect(49.2, 62.3, 83, 19.8),
                "工作通", 77, titleStyle, 2, 4.96, 14.69, List.of(28.24, 28.19));
        LineElement redRule = new LineElement("rule", 1, new Point(27, 118), new Point(183, 118),
                1.016, new ColorValue(250, 64, 6, 255), 3);
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(title), List.of(redRule),
                List.of(), List.of(), List.of(), List.of());
        Path output = temp.resolve("fixed-layout.docx");

        new PoiDocxRenderer().render(new DocumentModel("fixed.ofd", "test", 1, List.of(page), List.of()), output);

        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String xml = reopened.getDocument().xmlText();
            assertTrue(xml.contains("工作通"));
            assertTrue(xml.contains("position:absolute"));
            assertTrue(xml.contains("#FA4006"));
            assertFalse(xml.contains("txbxContent"));
            assertTrue(xml.contains("spacing"));
            assertTrue(reopened.getTables().isEmpty());
        }
    }

    @Test void tableOnOnePageDoesNotSwitchTheWholeDocumentToFlowLayout() throws Exception {
        PageModel first = pageWithText(1, new Rect(0, 0, 210, 297), "第一页固定正文");
        BorderStyle border = BorderStyle.solid(0.2, ColorValue.BLACK);
        CellModel cell = cell(0, 0, "表格内容", border);
        TableModel table = new TableModel("page-two-table", 2, new Rect(10, 50, 50, 20),
                List.of(10d, 60d), List.of(50d, 70d), List.of(cell), List.of(), 1d, List.of());
        PageModel second = new PageModel(2, new Rect(0, 0, 210, 297), List.of(), List.of(), List.of(),
                List.of(), List.of(table), List.of());
        Path output = temp.resolve("mixed-fixed-table.docx");

        new PoiDocxRenderer().render(new DocumentModel("mixed.ofd", "test", 2,
                List.of(first, second), List.of()), output);

        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String xml = reopened.getDocument().xmlText();
            assertTrue(xml.contains("第一页固定正文"));
            assertTrue(xml.contains("表格内容"));
            assertFalse(xml.contains("txbxContent"));
            assertEquals(1, reopened.getTables().size());
            assertEquals(0, reopened.getParagraphs().stream()
                    .filter(p -> p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr()).count());
            assertEquals(1, reopened.getParagraphs().stream()
                    .filter(p -> p.getCTP().isSetPPr()
                            && p.getCTP().getPPr().isSetPageBreakBefore()).count());
        }
    }

    @Test void rotatedTextUsesOneFallbackTextBoxButNormalTextDoesNot() throws Exception {
        TextBlock normal = text("normal", 15, 20, "普通正文");
        TextBlock rotated = new TextBlock("rotated", 1, new Rect(15, 40, 30, 6), "旋转文字", 45,
                FontStyle.defaults(), 1, 0, 0, List.of(),
                new Transform2D(0, 1, -1, 0, 0, 0));
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(normal, rotated),
                List.of(), List.of(),
                List.of(new ParagraphModel(normal.box(), List.of(normal), ParagraphModel.Alignment.LEFT, 0),
                        new ParagraphModel(rotated.box(), List.of(rotated), ParagraphModel.Alignment.LEFT, 0)),
                List.of(), List.of());
        Path output = temp.resolve("fallback.docx");

        new PoiDocxRenderer().render(new DocumentModel("test.ofd", "test", 1, List.of(page), List.of()), output);

        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String xml = reopened.getDocument().xmlText();
            assertTrue(xml.contains("普通正文"));
            assertTrue(xml.contains("旋转文字"));
            assertEquals(2, count(xml, "txbxContent"), "一个文本框包含开始和结束两个标记");
        }
    }

    @Test void overlappingMastheadUsesFallbackButBodyRemainsAParagraph() throws Exception {
        TextBlock agency = text("agency", 20, 20, "某某机关");
        TextBlock file = text("file", 80, 20, "文件");
        TextBlock body = text("body", 20, 50, "普通正文内容");
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(agency, file, body),
                List.of(), List.of(), List.of(
                new ParagraphModel(agency.box(), List.of(agency), ParagraphModel.Alignment.LEFT, 0),
                new ParagraphModel(file.box(), List.of(file), ParagraphModel.Alignment.LEFT, 0),
                new ParagraphModel(body.box(), List.of(body), ParagraphModel.Alignment.LEFT, 0)),
                List.of(), List.of());
        Path output = temp.resolve("overlapping-masthead.docx");

        new PoiDocxRenderer().render(new DocumentModel("test.ofd", "test", 1, List.of(page), List.of()), output);

        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String xml = reopened.getDocument().xmlText();
            assertEquals(4, count(xml, "txbxContent"), "两个复杂版头文本框各有开始和结束标记");
            assertTrue(reopened.getParagraphs().stream().anyMatch(p -> p.getText().contains("普通正文内容")));
        }
    }

    @Test void usesCanonicalCellAlignmentForEveryLineInTheCell() throws Exception {
        BorderStyle border = BorderStyle.solid(0.2, ColorValue.BLACK);
        Rect box = new Rect(10, 20, 50, 20);
        List<ParagraphModel> lines = List.of(
                new ParagraphModel(box, List.of(text("line1", 12, 22, "预估数")), ParagraphModel.Alignment.CENTER, 0),
                new ParagraphModel(box, List.of(text("line2", 25, 28, "量")), ParagraphModel.Alignment.RIGHT, 0));
        CellModel cell = new CellModel(0, 0, 1, 1, box, lines,
                border, border, border, border, null,
                ParagraphModel.Alignment.CENTER, CellModel.VerticalAlignment.CENTER);
        TableModel table = new TableModel("alignment", 1, box, List.of(10d, 60d), List.of(20d, 40d),
                List.of(cell), List.of(), 1, List.of());
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(), List.of(), List.of(),
                List.of(), List.of(table), List.of());
        Path output = temp.resolve("cell-alignment.docx");

        new PoiDocxRenderer().render(new DocumentModel("test.ofd", "test", 1, List.of(page), List.of()), output);

        try (XWPFDocument reopened = new XWPFDocument(Files.newInputStream(output))) {
            String xml = reopened.getDocument().xmlText();
            assertTrue(xml.contains("预估数"));
            assertTrue(xml.contains("量"));
            assertFalse(xml.contains("val=\"right\""));
        }
    }

    private PageModel pageWithText(int pageNumber, Rect pageBox, String value) {
        TextBlock block = new TextBlock("p" + pageNumber, pageNumber, new Rect(15, 15, 80, 6),
                value, 20, FontStyle.defaults(), 0);
        ParagraphModel paragraph = new ParagraphModel(block.box(), List.of(block), ParagraphModel.Alignment.LEFT, 0);
        return new PageModel(pageNumber, pageBox, List.of(block), List.of(), List.of(),
                List.of(paragraph), List.of(), List.of());
    }

    private CellModel cell(int row, int column, String value, BorderStyle border) {
        Rect box = new Rect(10 + column * 50, 50, 50, 20);
        return new CellModel(row, column, 1, 1, box,
                List.of(new ParagraphModel(box, List.of(text("c" + column, box.x() + 2, box.y() + 2, value)), ParagraphModel.Alignment.LEFT, 0)),
                border, border, border, border, null, ParagraphModel.Alignment.LEFT, CellModel.VerticalAlignment.CENTER);
    }
    private TextBlock text(String id, double x, double y, String value) {
        return new TextBlock(id, 1, new Rect(x, y, Math.max(10, value.length() * 5), 5), value, y + 4, FontStyle.defaults(), 0);
    }

    private int count(String value, String needle) {
        int count = 0;
        for (int at = value.indexOf(needle); at >= 0; at = value.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
