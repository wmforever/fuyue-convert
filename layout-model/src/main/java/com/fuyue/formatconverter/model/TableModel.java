package com.fuyue.formatconverter.model;

import java.util.List;

public record TableModel(String id, int pageNumber, Rect box, List<Double> xGrid, List<Double> yGrid,
                         List<CellModel> cells, List<MergeCellModel> merges, double confidence,
                         List<ConversionWarning> warnings) {
    public TableModel {
        id = id == null ? "" : id;
        xGrid = xGrid == null ? List.of() : List.copyOf(xGrid);
        yGrid = yGrid == null ? List.of() : List.copyOf(yGrid);
        cells = cells == null ? List.of() : List.copyOf(cells);
        merges = merges == null ? List.of() : List.copyOf(merges);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
    public int rowCount() { return Math.max(0, yGrid.size() - 1); }
    public int columnCount() { return Math.max(0, xGrid.size() - 1); }
    public List<RowModel> rows() {
        if (xGrid.size() < 2 || yGrid.size() < 2) return List.of();
        return java.util.stream.IntStream.range(0, rowCount()).mapToObj(row -> {
            double top = yGrid.get(row);
            double height = yGrid.get(row + 1) - top;
            List<CellModel> rowCells = cells.stream()
                    .filter(cell -> cell.row() == row)
                    .sorted(java.util.Comparator.comparingInt(CellModel::column))
                    .toList();
            return new RowModel(row, new Rect(xGrid.get(0), top,
                    xGrid.get(xGrid.size() - 1) - xGrid.get(0), height), height, false, rowCells);
        }).toList();
    }
}
