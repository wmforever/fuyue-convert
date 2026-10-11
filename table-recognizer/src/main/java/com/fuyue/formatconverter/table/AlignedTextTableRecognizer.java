package com.fuyue.formatconverter.table;

import com.fuyue.formatconverter.model.BorderStyle;
import com.fuyue.formatconverter.model.CellModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ParagraphModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TableModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.Transform2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Detects conservative borderless tables from repeated text columns and rows. */
public final class AlignedTextTableRecognizer implements BorderlessTableRecognizer {
    private static final double ROW_TOLERANCE_MM = 1.6d;
    private static final double COLUMN_TOLERANCE_MM = 5.0d;
    private static final double MAX_ROW_GAP_MM = 24d;

    @Override
    public List<TableModel> recognize(PageModel page) {
        List<TextBlock> source = page.textBlocks().stream()
                .filter(block -> !block.text().isBlank())
                .filter(block -> Math.abs(block.transform().rotationDegrees()) <= 0.5d)
                .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(t -> t.box().x()))
                .toList();
        List<RowBand> rows = rows(source);
        if (rows.size() < 3) return List.of();

        List<TableModel> tables = new ArrayList<>();
        int start = 0;
        while (start < rows.size()) {
            int end = start;
            while (end + 1 < rows.size()
                    && rows.get(end + 1).top() - rows.get(end).bottom() <= MAX_ROW_GAP_MM
                    && aligned(rows.get(end), rows.get(end + 1)) >= 2) {
                end++;
            }
            if (end - start + 1 >= 3) {
                TableModel candidate = table(page, rows.subList(start, end + 1), tables.size() + 1);
                if (candidate != null) tables.add(candidate);
            }
            start = Math.max(start + 1, end + 1);
        }
        return tables;
    }

    private TableModel table(PageModel page, List<RowBand> rows, int index) {
        int maxCells = rows.stream().mapToInt(row -> row.cells().size()).max().orElse(0);
        List<Double> centers = rows.stream().filter(row -> row.cells().size() == maxCells)
                .flatMap(row -> row.cells().stream())
                .map(cell -> cell.box().center().x()).sorted().toList();
        List<Double> columns = cluster(centers);
        if (columns.size() < 2 || columns.size() > 64) return null;
        int usableRows = 0;
        double alignmentScore = 0;
        for (RowBand row : rows) {
            int aligned = (int) row.cells().stream().mapToInt(cell -> nearest(columns, cell.box().center().x())).distinct().count();
            if (aligned >= 2) usableRows++;
            alignmentScore += Math.min(1d, aligned / (double) columns.size());
        }
        if (usableRows < 3 || alignmentScore / rows.size() < 0.82d) return null;

        double[] xGrid = xGrid(rows, columns);
        List<Double> yGrid = yGrid(rows);
        List<CellModel> cells = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            for (TextBlock text : rows.get(rowIndex).cells()) {
                int first = nearest(columns, text.box().center().x());
                int last = first;
                while (last + 1 < xGrid.length - 1 && text.box().right() > xGrid[last + 1] + 0.5d) last++;
                int span = Math.max(1, last - first + 1);
                Rect box = new Rect(xGrid[first], yGrid.get(rowIndex),
                        xGrid[first + span] - xGrid[first], yGrid.get(rowIndex + 1) - yGrid.get(rowIndex));
                ParagraphModel paragraph = new ParagraphModel(text.box(), List.of(text),
                        ParagraphModel.Alignment.LEFT, 0);
                cells.add(new CellModel(rowIndex, first, 1, span, box, List.of(paragraph),
                        BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE,
                        null, ParagraphModel.Alignment.LEFT, CellModel.VerticalAlignment.CENTER));
            }
        }
        if (cells.isEmpty()) return null;
        Rect box = new Rect(xGrid[0], yGrid.get(0), xGrid[xGrid.length - 1] - xGrid[0],
                yGrid.get(yGrid.size() - 1) - yGrid.get(0));
        double confidence = Math.min(0.97d, 0.84d + 0.13d * alignmentScore / rows.size());
        return new TableModel("p%d-borderless-table-%d".formatted(page.pageNumber(), index), page.pageNumber(),
                box, toList(xGrid), yGrid, cells, List.of(), confidence, List.of());
    }

    private List<RowBand> rows(List<TextBlock> source) {
        List<List<TextBlock>> grouped = new ArrayList<>();
        for (TextBlock text : source) {
            List<TextBlock> row = grouped.stream().filter(candidate ->
                    Math.abs(candidate.get(0).baselineY() - text.baselineY()) <= ROW_TOLERANCE_MM)
                    .findFirst().orElse(null);
            if (row == null) {
                row = new ArrayList<>();
                grouped.add(row);
            }
            row.add(text);
        }
        return grouped.stream().map(row -> {
            row.sort(Comparator.comparingDouble(t -> t.box().x()));
            List<TextBlock> cells = mergeNearby(row);
            double top = cells.stream().mapToDouble(t -> t.box().y()).min().orElse(0);
            double bottom = cells.stream().mapToDouble(t -> t.box().bottom()).max().orElse(top);
            return new RowBand(top, bottom, cells);
        }).sorted(Comparator.comparingDouble(RowBand::top)).toList();
    }

    private List<TextBlock> mergeNearby(List<TextBlock> row) {
        List<TextBlock> merged = new ArrayList<>();
        for (TextBlock text : row) {
            if (!merged.isEmpty()) {
                TextBlock previous = merged.get(merged.size() - 1);
                double gap = text.box().x() - previous.box().right();
                if (gap <= Math.max(2.5d, Math.min(6d, previous.box().height() * 1.8d))) {
                    Rect box = previous.box().union(text.box());
                    merged.set(merged.size() - 1, new TextBlock(previous.id(), previous.pageNumber(), box,
                            previous.text() + text.text(), (previous.baselineY() + text.baselineY()) / 2d,
                            previous.style(), previous.zOrder(), 0, 0, List.of(), Transform2D.IDENTITY));
                    continue;
                }
            }
            merged.add(text);
        }
        return merged;
    }

    private int aligned(RowBand first, RowBand second) {
        int matches = 0;
        Set<Integer> used = new HashSet<>();
        for (TextBlock a : first.cells()) {
            for (int index = 0; index < second.cells().size(); index++) {
                TextBlock b = second.cells().get(index);
                if (!used.contains(index) && Math.abs(a.box().center().x() - b.box().center().x()) <= COLUMN_TOLERANCE_MM) {
                    matches++;
                    used.add(index);
                    break;
                }
            }
        }
        return matches;
    }

    private List<Double> cluster(List<Double> values) {
        List<Double> result = new ArrayList<>();
        for (double value : values) {
            if (result.isEmpty() || Math.abs(value - result.get(result.size() - 1)) > COLUMN_TOLERANCE_MM) {
                result.add(value);
            } else {
                result.set(result.size() - 1, (result.get(result.size() - 1) + value) / 2d);
            }
        }
        return result;
    }

    private int nearest(List<Double> columns, double value) {
        int selected = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < columns.size(); index++) {
            double candidate = Math.abs(columns.get(index) - value);
            if (candidate < distance) { distance = candidate; selected = index; }
        }
        return selected;
    }

    private double[] xGrid(List<RowBand> rows, List<Double> columns) {
        double[] grid = new double[columns.size() + 1];
        double left = rows.stream().flatMap(row -> row.cells().stream()).mapToDouble(t -> t.box().x()).min().orElse(0);
        double right = rows.stream().flatMap(row -> row.cells().stream()).mapToDouble(t -> t.box().right()).max().orElse(left + 1);
        grid[0] = left;
        for (int index = 1; index < columns.size(); index++) grid[index] = (columns.get(index - 1) + columns.get(index)) / 2d;
        grid[grid.length - 1] = right;
        for (int index = 1; index < grid.length; index++) if (grid[index] <= grid[index - 1]) grid[index] = grid[index - 1] + 0.1d;
        return grid;
    }

    private List<Double> yGrid(List<RowBand> rows) {
        List<Double> grid = new ArrayList<>();
        grid.add(rows.get(0).top());
        for (int index = 1; index < rows.size(); index++) grid.add((rows.get(index - 1).bottom() + rows.get(index).top()) / 2d);
        grid.add(rows.get(rows.size() - 1).bottom());
        return grid;
    }

    private List<Double> toList(double[] values) {
        List<Double> result = new ArrayList<>(values.length);
        for (double value : values) result.add(value);
        return result;
    }

    private record RowBand(double top, double bottom, List<TextBlock> cells) { }
}
