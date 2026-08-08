package com.fuyue.formatconverter.table;

import com.fuyue.formatconverter.model.*;

import java.util.*;

public final class GridTableRecognizer {
    private final TableRecognitionConfig config;

    public GridTableRecognizer(TableRecognitionConfig config) { this.config = config; }

    public List<TableModel> recognize(PageModel page) {
        List<AxisLine> lines = normalize(page.lines());
        List<List<AxisLine>> components = connectedComponents(lines);
        List<TableModel> result = new ArrayList<>();
        int index = 1;
        for (List<AxisLine> component : components) {
            TableModel table = recognizeComponent(page, component, index++);
            if (table != null) result.add(table);
        }
        return result.stream().sorted(Comparator.comparingDouble(t -> t.box().y())).toList();
    }

    private TableModel recognizeComponent(PageModel page, List<AxisLine> lines, int index) {
        List<Double> xs = cluster(lines.stream().filter(AxisLine::vertical).map(AxisLine::axis).toList());
        List<Double> ys = cluster(lines.stream().filter(AxisLine::horizontal).map(AxisLine::axis).toList());
        if (xs.size() < 2 || ys.size() < 2) return null;
        int rows = ys.size() - 1;
        int cols = xs.size() - 1;
        if ((long) rows * cols > 10_000) return null;

        DisjointSet set = new DisjointSet(rows * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols - 1; c++) {
                if (!hasVertical(lines, xs.get(c + 1), ys.get(r), ys.get(r + 1))) {
                    set.union(cellIndex(r, c, cols), cellIndex(r, c + 1, cols));
                }
            }
        }
        for (int r = 0; r < rows - 1; r++) {
            for (int c = 0; c < cols; c++) {
                if (!hasHorizontal(lines, ys.get(r + 1), xs.get(c), xs.get(c + 1))) {
                    set.union(cellIndex(r, c, cols), cellIndex(r + 1, c, cols));
                }
            }
        }

        Map<Integer, List<GridPosition>> groups = new LinkedHashMap<>();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            groups.computeIfAbsent(set.find(cellIndex(r, c, cols)), ignored -> new ArrayList<>())
                    .add(new GridPosition(r, c));
        }

        List<CellModel> cells = new ArrayList<>();
        List<MergeCellModel> merges = new ArrayList<>();
        List<ConversionWarning> warnings = new ArrayList<>();
        boolean rectangular = true;
        BorderStyle border = BorderStyle.solid(0.2, ColorValue.BLACK);
        for (List<GridPosition> positions : groups.values()) {
            int minRow = positions.stream().mapToInt(GridPosition::row).min().orElseThrow();
            int maxRow = positions.stream().mapToInt(GridPosition::row).max().orElseThrow();
            int minCol = positions.stream().mapToInt(GridPosition::column).min().orElseThrow();
            int maxCol = positions.stream().mapToInt(GridPosition::column).max().orElseThrow();
            int expected = (maxRow - minRow + 1) * (maxCol - minCol + 1);
            if (positions.size() != expected) {
                rectangular = false;
                warnings.add(new ConversionWarning(WarningCode.AMBIGUOUS_MERGE,
                        "检测到非矩形合并区域，已按原子单元格降级", page.pageNumber(),
                        rect(xs, ys, minRow, maxRow, minCol, maxCol)));
                for (GridPosition position : positions) {
                    cells.add(createCell(page, lines, xs, ys, position.row, position.row,
                            position.column, position.column, border));
                }
                continue;
            }
            CellModel cell = createCell(page, lines, xs, ys, minRow, maxRow, minCol, maxCol, border);
            cells.add(cell);
            if (cell.rowSpan() > 1 || cell.columnSpan() > 1) {
                merges.add(new MergeCellModel(minRow, minCol, cell.rowSpan(), cell.columnSpan(), 0.95));
            }
        }

        Rect box = new Rect(xs.get(0), ys.get(0), xs.get(xs.size() - 1) - xs.get(0), ys.get(ys.size() - 1) - ys.get(0));
        double outer = outerCoverage(lines, xs, ys);
        double confidence = Math.min(1d, 0.65 + 0.35 * outer) * (rectangular ? 1d : 0.6d);
        if (confidence < 0.85) {
            warnings.add(new ConversionWarning(WarningCode.TABLE_RECOGNITION_UNRELIABLE,
                    "表格网格置信度为 %.2f，请人工复核".formatted(confidence), page.pageNumber(), box));
        }
        if (confidence < config.minimumConfidence()) return null;
        cells = normalizeColumnAlignments(cells, cols);
        return new TableModel("p%d-table-%d".formatted(page.pageNumber(), index), page.pageNumber(),
                box, xs, ys, cells, merges, confidence, warnings);
    }

    private List<CellModel> normalizeColumnAlignments(List<CellModel> cells, int columnCount) {
        Map<Integer, ParagraphModel.Alignment> columnAlignment = new HashMap<>();
        for (int column = 0; column < columnCount; column++) {
            EnumMap<ParagraphModel.Alignment, Integer> votes = new EnumMap<>(ParagraphModel.Alignment.class);
            for (CellModel cell : cells) {
                if (cell.column() != column || cell.columnSpan() != 1 || cell.paragraphs().isEmpty()) continue;
                votes.merge(cell.horizontalAlignment(), 1, Integer::sum);
            }
            if (!votes.isEmpty()) {
                ParagraphModel.Alignment selected = Arrays.stream(ParagraphModel.Alignment.values())
                        .max(Comparator.comparingInt((ParagraphModel.Alignment value) -> votes.getOrDefault(value, 0))
                                .thenComparingInt(this::alignmentTieBreak))
                        .orElse(ParagraphModel.Alignment.LEFT);
                columnAlignment.put(column, selected);
            }
        }
        return cells.stream().map(cell -> {
            ParagraphModel.Alignment selected = cell.columnSpan() == 1
                    ? columnAlignment.getOrDefault(cell.column(), cell.horizontalAlignment())
                    : cell.horizontalAlignment();
            return new CellModel(cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan(), cell.box(),
                    cell.paragraphs(), cell.top(), cell.right(), cell.bottom(), cell.left(), cell.fill(),
                    selected, cell.verticalAlignment());
        }).toList();
    }

    private int alignmentTieBreak(ParagraphModel.Alignment alignment) {
        return switch (alignment) {
            case CENTER -> 3;
            case LEFT -> 2;
            case RIGHT -> 1;
            case JUSTIFY -> 0;
        };
    }

    private CellModel createCell(PageModel page, List<AxisLine> lines, List<Double> xs, List<Double> ys,
                                 int minRow, int maxRow, int minCol, int maxCol, BorderStyle fallbackBorder) {
        Rect box = rect(xs, ys, minRow, maxRow, minCol, maxCol);
        List<TextBlock> content = page.textBlocks().stream()
                .filter(t -> box.contains(t.box().center(), config.axisToleranceMm()))
                .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(t -> t.box().x()))
                .toList();
        List<ParagraphModel> paragraphs = groupCellLines(content, box);
        ParagraphModel.Alignment horizontal = inferCellAlignment(content, box);
        BorderStyle top = hasHorizontal(lines, ys.get(minRow), xs.get(minCol), xs.get(maxCol + 1)) ? fallbackBorder : BorderStyle.NONE;
        BorderStyle bottom = hasHorizontal(lines, ys.get(maxRow + 1), xs.get(minCol), xs.get(maxCol + 1)) ? fallbackBorder : BorderStyle.NONE;
        BorderStyle left = hasVertical(lines, xs.get(minCol), ys.get(minRow), ys.get(maxRow + 1)) ? fallbackBorder : BorderStyle.NONE;
        BorderStyle right = hasVertical(lines, xs.get(maxCol + 1), ys.get(minRow), ys.get(maxRow + 1)) ? fallbackBorder : BorderStyle.NONE;
        return new CellModel(minRow, minCol, maxRow - minRow + 1, maxCol - minCol + 1, box,
                paragraphs, top, right, bottom, left, null, horizontal, CellModel.VerticalAlignment.CENTER);
    }

    private List<ParagraphModel> groupCellLines(List<TextBlock> blocks, Rect cell) {
        List<List<TextBlock>> lines = new ArrayList<>();
        for (TextBlock block : blocks) {
            List<TextBlock> match = lines.stream()
                    .filter(line -> Math.abs(line.get(0).baselineY() - block.baselineY()) <= 1.0)
                    .findFirst().orElse(null);
            if (match == null) { match = new ArrayList<>(); lines.add(match); }
            match.add(block);
        }
        return lines.stream().map(line -> {
            line.sort(Comparator.comparingDouble(t -> t.box().x()));
            Rect box = line.stream().map(TextBlock::box).reduce(Rect::union).orElse(cell);
            return new ParagraphModel(box, line, inferCellAlignment(line, cell), 0);
        }).toList();
    }

    private ParagraphModel.Alignment inferCellAlignment(List<TextBlock> content, Rect box) {
        if (content.isEmpty()) return ParagraphModel.Alignment.LEFT;
        Rect text = content.stream().map(TextBlock::box).reduce(Rect::union).orElse(box);
        double left = text.x() - box.x();
        double right = box.right() - text.right();
        if (Math.abs(left - right) <= 1.5) return ParagraphModel.Alignment.CENTER;
        if (right + 0.5 < left) return ParagraphModel.Alignment.RIGHT;
        return ParagraphModel.Alignment.LEFT;
    }

    private double outerCoverage(List<AxisLine> lines, List<Double> xs, List<Double> ys) {
        int present = 0;
        if (hasHorizontal(lines, ys.get(0), xs.get(0), xs.get(xs.size() - 1))) present++;
        if (hasHorizontal(lines, ys.get(ys.size() - 1), xs.get(0), xs.get(xs.size() - 1))) present++;
        if (hasVertical(lines, xs.get(0), ys.get(0), ys.get(ys.size() - 1))) present++;
        if (hasVertical(lines, xs.get(xs.size() - 1), ys.get(0), ys.get(ys.size() - 1))) present++;
        return present / 4d;
    }

    private boolean hasHorizontal(List<AxisLine> lines, double y, double fromX, double toX) {
        return covered(lines, true, y, fromX, toX) >= config.coverageThreshold();
    }
    private boolean hasVertical(List<AxisLine> lines, double x, double fromY, double toY) {
        return covered(lines, false, x, fromY, toY) >= config.coverageThreshold();
    }

    private double covered(List<AxisLine> lines, boolean horizontal, double axis, double from, double to) {
        double length = Math.max(0.0001, to - from);
        List<double[]> ranges = lines.stream()
                .filter(line -> line.horizontal() == horizontal && Math.abs(line.axis() - axis) <= config.axisToleranceMm())
                .map(line -> new double[]{Math.max(from, line.from()), Math.min(to, line.to())})
                .filter(range -> range[1] > range[0]).sorted(Comparator.comparingDouble(a -> a[0])).toList();
        double total = 0;
        double start = Double.NaN, end = Double.NaN;
        for (double[] range : ranges) {
            if (Double.isNaN(start)) { start = range[0]; end = range[1]; }
            else if (range[0] <= end + config.gapToleranceMm()) end = Math.max(end, range[1]);
            else { total += end - start; start = range[0]; end = range[1]; }
        }
        if (!Double.isNaN(start)) total += end - start;
        return Math.min(1d, total / length);
    }

    private List<AxisLine> normalize(List<LineElement> source) {
        List<AxisLine> raw = new ArrayList<>();
        for (LineElement line : source) {
            if (line.horizontal(config.axisToleranceMm())) {
                raw.add(new AxisLine(true, (line.start().y() + line.end().y()) / 2d, line.minX(), line.maxX()));
            } else if (line.vertical(config.axisToleranceMm())) {
                raw.add(new AxisLine(false, (line.start().x() + line.end().x()) / 2d, line.minY(), line.maxY()));
            }
        }
        raw.sort(Comparator.comparing(AxisLine::horizontal).thenComparingDouble(AxisLine::axis).thenComparingDouble(AxisLine::from));
        List<AxisLine> merged = new ArrayList<>();
        for (AxisLine line : raw) {
            AxisLine match = null;
            for (int i = merged.size() - 1; i >= 0; i--) {
                AxisLine candidate = merged.get(i);
                if (candidate.horizontal() == line.horizontal()
                        && Math.abs(candidate.axis() - line.axis()) <= config.axisToleranceMm()
                        && line.from() <= candidate.to() + config.gapToleranceMm()) {
                    match = candidate;
                    merged.set(i, new AxisLine(line.horizontal(), (candidate.axis() + line.axis()) / 2d,
                            Math.min(candidate.from(), line.from()), Math.max(candidate.to(), line.to())));
                    break;
                }
            }
            if (match == null) merged.add(line);
        }
        return merged;
    }

    private List<List<AxisLine>> connectedComponents(List<AxisLine> lines) {
        DisjointSet set = new DisjointSet(lines.size());
        for (int i = 0; i < lines.size(); i++) for (int j = i + 1; j < lines.size(); j++) {
            if (touch(lines.get(i), lines.get(j))) set.union(i, j);
        }
        Map<Integer, List<AxisLine>> groups = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) groups.computeIfAbsent(set.find(i), ignored -> new ArrayList<>()).add(lines.get(i));
        return groups.values().stream().filter(group -> group.size() >= 4).toList();
    }

    private boolean touch(AxisLine a, AxisLine b) {
        double e = config.gapToleranceMm();
        if (a.horizontal() == b.horizontal()) {
            return Math.abs(a.axis() - b.axis()) <= config.axisToleranceMm() && a.from() <= b.to() + e && b.from() <= a.to() + e;
        }
        AxisLine h = a.horizontal() ? a : b;
        AxisLine v = a.horizontal() ? b : a;
        return v.axis() >= h.from() - e && v.axis() <= h.to() + e && h.axis() >= v.from() - e && h.axis() <= v.to() + e;
    }

    private List<Double> cluster(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        List<List<Double>> groups = new ArrayList<>();
        for (Double value : sorted) {
            if (groups.isEmpty() || Math.abs(value - groups.get(groups.size() - 1).get(0)) > config.axisToleranceMm()) {
                groups.add(new ArrayList<>());
            }
            groups.get(groups.size() - 1).add(value);
        }
        return groups.stream().map(group -> group.stream().mapToDouble(Double::doubleValue).average().orElseThrow()).toList();
    }

    private Rect rect(List<Double> xs, List<Double> ys, int minRow, int maxRow, int minCol, int maxCol) {
        return new Rect(xs.get(minCol), ys.get(minRow), xs.get(maxCol + 1) - xs.get(minCol), ys.get(maxRow + 1) - ys.get(minRow));
    }
    private int cellIndex(int row, int column, int columns) { return row * columns + column; }
    private record GridPosition(int row, int column) {}
    private record AxisLine(boolean horizontal, double axis, double from, double to) { boolean vertical() { return !horizontal; } }

    private static final class DisjointSet {
        private final int[] parent;
        private DisjointSet(int size) { parent = new int[size]; for (int i = 0; i < size; i++) parent[i] = i; }
        private int find(int x) { if (parent[x] != x) parent[x] = find(parent[x]); return parent[x]; }
        private void union(int a, int b) { int ra = find(a), rb = find(b); if (ra != rb) parent[rb] = ra; }
    }
}
