package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.util.*;

/** TXT-only reconstruction of repeated short fragments, never merged column rows. */
final class OcrFragmentedColumns {
    private OcrFragmentedColumns() { }

    static OcrReadingOrder.Result arrange(List<TextBlock> blocks, double pageWidth, long deadline) {
        var fallback = new OcrReadingOrder.Result(blocks.stream().map(TextBlock::text).toList(), false, true);
        if (blocks.size() < 27 || blocks.size() > 500 || !Double.isFinite(pageWidth) || pageWidth <= 0
                || System.nanoTime() >= deadline) return fallback;
        var boxes = new IdentityHashMap<TextBlock, Rect>();
        for (var block : blocks) {
            if (System.nanoTime() >= deadline || block.ocrWords().isEmpty() || block.ocrWords().size() > 2
                    || block.text().codePoints().filter(Character::isLetter).count() < 3
                    || !compact(block.text()).equals(compact(OcrDeskewSelection.join(block.ocrWords())))) return fallback;
            Rect bounds = block.box();
            for (var word : block.ocrWords()) bounds = bounds.union(word.box());
            if (!Double.isFinite(bounds.x()) || !Double.isFinite(bounds.y()) || !Double.isFinite(bounds.width())
                    || !Double.isFinite(bounds.height()) || bounds.width() <= 0 || bounds.height() <= 0
                    || bounds.x() < 0 || bounds.right() > pageWidth) return fallback;
            boxes.put(block, bounds);
        }
        double[] heights = boxes.values().stream().mapToDouble(Rect::height).sorted().toArray();
        double height = heights[heights.length / 2], minimumGap = Math.max(pageWidth * .08, height * 4);
        var sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingDouble(b -> boxes.get(b).x()));
        var columns = new ArrayList<List<TextBlock>>();
        columns.add(new ArrayList<>());
        double right = boxes.get(sorted.get(0)).right();
        for (var block : sorted) {
            if (System.nanoTime() >= deadline) return fallback;
            Rect bounds = boxes.get(block);
            if (bounds.height() < height * .65 || bounds.height() > height * 1.5) return fallback;
            if (bounds.x() - right >= minimumGap) columns.add(new ArrayList<>());
            columns.get(columns.size()-1).add(block); right = Math.max(right, bounds.right());
        }
        // More columns, spanning headings, short numeric cells and complete lines retain their prior fallback.
        if (columns.size() != 3) return fallback;
        // Wide horizontal gutters also occur between successive sections of a
        // document. Require simultaneous vertical coverage, as in the existing
        // two-column inference, before imposing left-to-right column order.
        double commonStart = Double.NEGATIVE_INFINITY, commonEnd = Double.POSITIVE_INFINITY;
        double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
        for (var column : columns) {
            if (System.nanoTime() >= deadline) return fallback;
            double top = column.stream().mapToDouble(b -> boxes.get(b).center().y()).min().orElse(0);
            double bottom = column.stream().mapToDouble(b -> boxes.get(b).center().y()).max().orElse(0);
            commonStart = Math.max(commonStart, top); commonEnd = Math.min(commonEnd, bottom);
            first = Math.min(first, top); last = Math.max(last, bottom);
        }
        if (commonEnd - commonStart < (last - first) * .60 || System.nanoTime() >= deadline) return fallback;
        var output = new ArrayList<String>();
        var used = Collections.newSetFromMap(new IdentityHashMap<TextBlock, Boolean>());
        for (var column : columns) {
            column.sort(Comparator.comparingDouble(b -> boxes.get(b).center().y()));
            var rows = new ArrayList<List<TextBlock>>();
            double center = Double.NEGATIVE_INFINITY;
            for (var block : column) {
                if (System.nanoTime() >= deadline) return fallback;
                double y = boxes.get(block).center().y();
                if (y - center > height * .35) { rows.add(new ArrayList<>()); center = y; }
                rows.get(rows.size()-1).add(block);
            }
            if (rows.size() < 3 || rows.size() > 100) return fallback;
            int fragments = rows.get(0).size();
            if (fragments < 3 || fragments > 6) return fallback;
            double previousBottom = Double.NEGATIVE_INFINITY, leftEdge = Double.NaN, rightEdge = Double.NaN;
            for (var row : rows) {
                if (System.nanoTime() >= deadline || row.size() != fragments) return fallback;
                row.sort(Comparator.comparingDouble(b -> boxes.get(b).x()));
                Rect bounds = boxes.get(row.get(0)); double lastRight = bounds.x();
                int rowWords = 0;
                for (var block : row) {
                    Rect next = boxes.get(block);
                    // Independent fragments must be distinct, neighboring and aligned, never overlapping cells.
                    if (!used.add(block) || next.x() < lastRight || next.x() - lastRight > height * 2) return fallback;
                    lastRight = next.right(); bounds = bounds.union(next); rowWords += block.ocrWords().size();
                }
                String line = String.join(" ", row.stream().map(TextBlock::text).toList());
                if (line.codePoints().filter(Character::isLetter).count() < 12 || rowWords < 3
                        || bounds.y() - previousBottom < height * 2) return fallback;
                if (Double.isNaN(leftEdge)) { leftEdge = bounds.x(); rightEdge = bounds.right(); }
                if (Math.abs(bounds.x()-leftEdge) > height * .5 || Math.abs(bounds.right()-rightEdge) > height * .5)
                    return fallback;
                previousBottom = bounds.bottom(); output.add(line);
            }
        }
        // Use each immutable source block exactly once. Joining whole strings inserts whitespace only;
        // numeric punctuation, signs, internal separators and source word boxes are never regenerated.
        if (used.size() != blocks.size() || System.nanoTime() >= deadline
                || !inventory(output).equals(inventory(fallback.lines()))) return fallback;
        boolean adjusted = !compact(String.join("",output)).equals(compact(String.join("",fallback.lines())));
        return new OcrReadingOrder.Result(List.copyOf(output), adjusted, true, true);
    }

    private static String compact(String text) { return text.replaceAll("\\s", ""); }
    private static Map<Integer,Integer> inventory(List<String> lines) {
        var result = new HashMap<Integer,Integer>();
        for (var line : lines) line.codePoints().filter(c -> !Character.isWhitespace(c)).forEach(c -> result.merge(c,1,Integer::sum));
        return result;
    }
}
