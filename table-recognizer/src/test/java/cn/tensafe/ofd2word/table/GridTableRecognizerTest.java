package cn.tensafe.ofd2word.table;

import cn.tensafe.ofd2word.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridTableRecognizerTest {
    private final GridTableRecognizer recognizer = new GridTableRecognizer(TableRecognitionConfig.defaults());

    @Test void recognizesTwoByTwoTableAndAssignsText() {
        List<LineElement> lines = grid(true);
        TextBlock text = new TextBlock("t1", 1, new Rect(5, 5, 15, 5), "姓名", 9,
                FontStyle.defaults(), 1);
        PageModel page = page(lines, List.of(text));
        TableModel table = assertSingle(page);
        assertEquals(2, table.rowCount());
        assertEquals(2, table.columnCount());
        assertEquals(4, table.cells().size());
        assertEquals("姓名", table.cells().get(0).paragraphs().get(0).runs().get(0).text());
    }

    @Test void recognizesHorizontalMerge() {
        TableModel table = assertSingle(page(grid(false), List.of()));
        assertEquals(3, table.cells().size());
        CellModel merged = table.cells().stream().filter(c -> c.row() == 0).findFirst().orElseThrow();
        assertEquals(2, merged.columnSpan());
        assertEquals(1, table.merges().size());
    }

    @Test void smallLineGapIsRepaired() {
        List<LineElement> lines = new ArrayList<>(grid(true));
        lines.removeIf(l -> l.id().equals("h0"));
        lines.add(line("h0a", 0, 0, 49.8, 0));
        lines.add(line("h0b", 50.2, 0, 100, 0));
        assertEquals(4, assertSingle(page(lines, List.of())).cells().size());
    }

    @Test void recognizesVerticalMerge() {
        List<LineElement> lines = new ArrayList<>();
        lines.add(line("top", 0, 0, 100, 0));
        lines.add(line("middle-right", 50, 20, 100, 20));
        lines.add(line("bottom", 0, 40, 100, 40));
        lines.add(line("left", 0, 0, 0, 40));
        lines.add(line("center", 50, 0, 50, 40));
        lines.add(line("right", 100, 0, 100, 40));
        TableModel table = assertSingle(page(lines, List.of()));
        CellModel merged = table.cells().stream().filter(c -> c.column() == 0).findFirst().orElseThrow();
        assertEquals(2, merged.rowSpan());
        assertEquals(3, table.cells().size());
    }

    @Test void normalizesAlignmentAcrossTheSameColumn() {
        List<LineElement> lines = new ArrayList<>();
        for (int row = 0; row <= 3; row++) lines.add(line("h" + row, 0, row * 20, 40, row * 20));
        lines.add(line("left", 0, 0, 0, 60));
        lines.add(line("right", 40, 0, 40, 60));
        List<TextBlock> texts = List.of(
                new TextBlock("header", 1, new Rect(14, 5, 12, 5), "名称", 9, FontStyle.defaults(), 0),
                new TextBlock("short", 1, new Rect(24, 25, 14, 5), "短文本", 29, FontStyle.defaults(), 0),
                new TextBlock("long", 1, new Rect(12, 45, 16, 5), "长文本", 49, FontStyle.defaults(), 0));

        TableModel table = assertSingle(page(lines, texts));

        assertTrue(table.cells().stream().allMatch(cell ->
                cell.horizontalAlignment() == ParagraphModel.Alignment.CENTER));
    }

    private TableModel assertSingle(PageModel page) {
        List<TableModel> tables = recognizer.recognize(page);
        assertEquals(1, tables.size());
        return tables.get(0);
    }
    private PageModel page(List<LineElement> lines, List<TextBlock> texts) {
        return new PageModel(1, new Rect(0, 0, 210, 297), texts, lines, List.of(), List.of(), List.of(), List.of());
    }
    private List<LineElement> grid(boolean completeMiddleVertical) {
        List<LineElement> lines = new ArrayList<>();
        lines.add(line("h0", 0, 0, 100, 0));
        lines.add(line("h1", 0, 20, 100, 20));
        lines.add(line("h2", 0, 40, 100, 40));
        lines.add(line("v0", 0, 0, 0, 40));
        lines.add(line("v2", 100, 0, 100, 40));
        lines.add(line("v1", 50, completeMiddleVertical ? 0 : 20, 50, 40));
        return lines;
    }
    private LineElement line(String id, double x1, double y1, double x2, double y2) {
        return new LineElement(id, 1, new Point(x1, y1), new Point(x2, y2), 0.2, ColorValue.BLACK, 0);
    }
}
