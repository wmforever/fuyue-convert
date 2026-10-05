package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.awt.image.BufferedImage;
import java.util.*;

/** Bounded recovery for a complete, light-paper rectangular grid. Never edits source pixels. */
final class OcrRuledGrid {
    record Band(int first, int last) { }
    record Cell(int x, int y, int width, int height, Rect box) { }
    record Grid(Rect box, List<Cell> cells, List<Rect> rules) { }
    private OcrRuledGrid() { }

    static Grid detectForRecovery(BufferedImage image, Rect physical,
            TesseractOcrConverter.RecognitionResult original, long deadline) {
        // Grid recovery can only replace a low-confidence, nonnumeric word.
        // Avoid scanning every source pixel when the recognition already rules it out.
        if (original.blocks().isEmpty() || original.wordCount() > 500
                || original.blocks().stream().flatMap(b -> b.ocrWords().stream())
                    .noneMatch(OcrRuledGrid::unreliableNonnumeric)) return null;
        return detect(image, physical, deadline);
    }

    static Grid detect(BufferedImage image, Rect physical, long deadline) {
        int width = image.getWidth(), height = image.getHeight();
        if (width < 300 || height < 200 || (long) width * height > 25_000_000) return null;
        int[] rows = new int[height];
        long paper = 0;
        for (int y = 0; y < height; y++) {
            if (System.nanoTime() >= deadline) return null;
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                if ((rgb >>> 24) != 255) return null;
                if (Math.min(r, Math.min(g, b)) >= 220 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 15) paper++;
                if (dark(rgb)) rows[y]++;
            }
        }
        if (paper < (long) width * height * .85) return null;
        var ys = bands(rows, width * .8);
        if (ys.size() < 3 || ys.size() > 17) return null;
        int top = ys.get(0).first(), bottom = ys.get(ys.size() - 1).last();
        int[] columns = new int[width];
        for (int y = top; y <= bottom; y++) {
            if (System.nanoTime() >= deadline) return null;
            for (int x = 0; x < width; x++) if (dark(image.getRGB(x, y))) columns[x]++;
        }
        var xs = bands(columns, (bottom - top + 1) * .985);
        if (xs.size() < 3 || xs.size() > 9 || (xs.size() - 1) * (ys.size() - 1) > 64) return null;
        int left = xs.get(0).first(), right = xs.get(xs.size() - 1).last();
        // Thick bars, missing edges, merged cells and broken intersections do not establish independent cells.
        for (var y : ys) {
            if (y.last() - y.first() + 1 > Math.max(3, height / 150)) return null;
            for (int x = left; x <= right; x++) {
                if (System.nanoTime() >= deadline || !dark(image.getRGB(x, (y.first() + y.last()) / 2))) return null;
            }
        }
        for (var x : xs) {
            if (x.last() - x.first() + 1 > Math.max(3, width / 200)) return null;
            for (int y = top; y <= bottom; y++) {
                if (System.nanoTime() >= deadline || !dark(image.getRGB((x.first() + x.last()) / 2, y))) return null;
            }
        }
        List<Cell> cells = new ArrayList<>();
        for (int row = 1; row < ys.size(); row++) for (int column = 1; column < xs.size(); column++) {
            int x = xs.get(column - 1).last() + 1, y = ys.get(row - 1).last() + 1;
            int w = xs.get(column).first() - x, h = ys.get(row).first() - y;
            if (w < 30 || h < 20) return null;
            cells.add(new Cell(x, y, w, h, map(x, y, w, h, width, height, physical)));
        }
        List<Rect> rules = new ArrayList<>();
        for (var x : xs) rules.add(map(x.first(), top, x.last() - x.first() + 1, bottom - top + 1, width, height, physical));
        for (var y : ys) rules.add(map(left, y.first(), right - left + 1, y.last() - y.first() + 1, width, height, physical));
        return new Grid(map(left, top, right - left + 1, bottom - top + 1, width, height, physical), List.copyOf(cells), List.copyOf(rules));
    }

    /** Reorder complete native fields only; never fill an empty cell or concatenate numeric fragments. */
    static String nativeText(Grid grid, PageModel page) {
        if (grid == null || page.textBlocks().size() > 500 || page.textBlocks().isEmpty()) return null;
        List<List<TextBlock>> cells = new ArrayList<>();
        for (var ignored : grid.cells()) cells.add(new ArrayList<>());
        List<TextBlock> before = new ArrayList<>(), after = new ArrayList<>();
        for (var block : page.textBlocks()) {
            if (!block.ocrWords().isEmpty() || !Transform2D.IDENTITY.equals(block.transform()) || block.text().isBlank()) return null;
            int index = cell(grid, block.box());
            if (index >= 0) cells.get(index).add(block);
            else if (block.box().bottom() < grid.box().y()) before.add(block);
            else if (block.box().y() > grid.box().bottom()) after.add(block);
            else return null;
        }
        StringBuilder text = new StringBuilder(outsideText(page, before));
        double y = Double.NaN;
        for (int i = 0; i < cells.size(); i++) {
            var blocks = cells.get(i);
            if (blocks.isEmpty()) return null;
            blocks.sort(Comparator.comparingDouble(b -> b.box().x()));
            double baseline = blocks.get(0).baselineY();
            StringBuilder value = new StringBuilder();
            for (var block : blocks) {
                if (Math.abs(block.baselineY() - baseline) > 1.5) return null;
                if (!value.isEmpty()) {
                    int last = value.codePointBefore(value.length()), first = block.text().codePointAt(0);
                    if (!Character.isWhitespace(last) && !Character.isWhitespace(first)
                            && !(Character.UnicodeScript.of(last) == Character.UnicodeScript.HAN
                            && Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN)) return null;
                }
                value.append(block.text());
            }
            double nextY = grid.cells().get(i).box().y();
            if (i > 0) text.append(nextY == y ? "\t" : System.lineSeparator());
            text.append(value.toString().strip()); y = nextY;
        }
        return text.append(System.lineSeparator()).append(outsideText(page, after)).toString();
    }

    private static String outsideText(PageModel page, List<TextBlock> blocks) {
        return OfdToTextConverter.text(List.of(new PageModel(page.pageNumber(), page.physicalBox(), blocks,
                List.of(), List.of(), List.of(), List.of(), List.of())));
    }

    private static boolean dark(int rgb) { return (rgb >> 16 & 255) < 80 && (rgb >> 8 & 255) < 80 && (rgb & 255) < 80; }
    private static List<Band> bands(int[] values, double threshold) {
        List<Band> result = new ArrayList<>();
        for (int i = 0; i < values.length; i++) if (values[i] > threshold) {
            int start = i;
            while (i + 1 < values.length && values[i + 1] > threshold) i++;
            result.add(new Band(start, i));
        }
        return result;
    }
    private static Rect map(int x, int y, int w, int h, int width, int height, Rect physical) {
        return new Rect(physical.x() + x * physical.width() / width, physical.y() + y * physical.height() / height,
                w * physical.width() / width, h * physical.height() / height);
    }
    private static boolean contains(Rect outer, Rect inner) {
        return outer.contains(new Point(inner.x(), inner.y()), .001) && outer.contains(new Point(inner.right(), inner.bottom()), .001);
    }
    private static int cell(Grid grid, Rect box) {
        for (int i = 0; i < grid.cells().size(); i++) if (contains(grid.cells().get(i).box(), box)) return i;
        return -1;
    }
    private static boolean anomaly(Grid grid, TextBlock.OcrWord word) {
        return unreliableNonnumeric(word)
                && contains(grid.box(), word.box()) && cell(grid, word.box()) < 0;
    }
    private static boolean unreliableNonnumeric(TextBlock.OcrWord word) {
        return word.confidence() < .35 && !word.text().codePoints().anyMatch(Character::isDigit);
    }
    static boolean eligible(Grid grid, TesseractOcrConverter.RecognitionResult original) {
        return grid != null && !original.blocks().isEmpty() && original.wordCount() <= 500
                && original.blocks().stream().flatMap(b -> b.ocrWords().stream()).anyMatch(w -> anomaly(grid, w));
    }

    static TesseractOcrConverter.RecognitionResult select(Grid grid, TesseractOcrConverter.RecognitionResult original,
            List<TesseractOcrConverter.RecognitionResult> candidates, double minimumConfidence, long deadline) {
        if (!eligible(grid, original) || candidates.size() != grid.cells().size() || System.nanoTime() >= deadline) return original;
        List<List<TextBlock.OcrWord>> source = new ArrayList<>();
        for (var ignored : grid.cells()) source.add(new ArrayList<>());
        List<TextBlock> outside = new ArrayList<>();
        List<String> superseded = new ArrayList<>();
        for (var block : original.blocks()) {
            if (System.nanoTime() >= deadline || block.ocrWords().isEmpty() || !Transform2D.IDENTITY.equals(block.transform())) return original;
            if (block.box().intersectionArea(grid.box()) == 0) { outside.add(block); continue; }
            for (var word : block.ocrWords()) {
                int index = cell(grid, word.box());
                if (index >= 0) source.get(index).add(word);
                else if (anomaly(grid, word)) superseded.add(word.text());
                else return original;
            }
        }
        if (superseded.isEmpty() || superseded.size() > 16) return original;
        List<TextBlock> recovered = new ArrayList<>();
        int added = 0, totalWords = 0;
        double confidence = 0;
        for (int i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            Rect cellBox = grid.cells().get(i).box();
            if (System.nanoTime() >= deadline || candidate.blocks().isEmpty() || candidate.wordCount() > 64) return original;
            var words = candidate.blocks().stream().flatMap(b -> b.ocrWords().stream()).toList();
            if (words.isEmpty() || words.stream().anyMatch(w -> w.confidence() < Math.max(.75, minimumConfidence)
                    || !contains(cellBox, w.box()))) return original;
            var previous = source.get(i);
            // A cell containing an existing number must retain its entire literal field, including signs,
            // punctuation, units and leading zeroes. Additional adjacent tokens cannot extend a number.
            if (previous.stream().anyMatch(w -> w.text().codePoints().anyMatch(Character::isDigit))
                    && !previous.stream().map(TextBlock.OcrWord::text).toList().equals(words.stream().map(TextBlock.OcrWord::text).toList())) return original;
            Map<TextBlock.OcrWord, TextBlock.OcrWord> replacements = new IdentityHashMap<>();
            int offset = 0;
            for (var old : previous) {
                int match = -1;
                for (int j = offset; j < words.size(); j++) {
                    var next = words.get(j);
                    if (old.text().equals(next.text()) && old.box().intersectionArea(next.box())
                            >= Math.min(old.box().width() * old.box().height(), next.box().width() * next.box().height()) * .7) { match = j; break; }
                }
                if (match < 0) return original;
                replacements.put(words.get(match), old); offset = match + 1;
            }
            added += words.size() - previous.size();
            for (var block : candidate.blocks()) {
                var retained = block.ocrWords().stream().map(w -> replacements.getOrDefault(w, w)).toList();
                Rect box = retained.get(0).box();
                for (var word : retained) { box = box.union(word.box()); confidence += word.confidence(); totalWords++; }
                recovered.add(new TextBlock("ocr-grid-p" + block.pageNumber() + "-c" + i + "-" + recovered.size(), block.pageNumber(),
                        box, block.text(), box.y() + box.height() * .85, block.style(), recovered.size() + 1,
                        0, 0, List.of(), Transform2D.IDENTITY, retained));
            }
        }
        if (added <= superseded.size() || totalWords > 500 || confidence / totalWords < Math.max(minimumConfidence, original.confidence() + .05)) return original;
        // Whole outside-grid lines remain verbatim. A spanning line was already rejected above.
        for (var block : outside) {
            for (var word : block.ocrWords()) { confidence += word.confidence(); totalWords++; }
        }
        List<TextBlock> combined = new ArrayList<>();
        outside.stream().filter(b -> b.box().bottom() <= grid.box().y()).forEach(combined::add);
        if (outside.stream().anyMatch(b -> b.box().bottom() > grid.box().y() && b.box().y() < grid.box().bottom())) return original;
        combined.addAll(recovered);
        outside.stream().filter(b -> b.box().y() >= grid.box().bottom()).forEach(combined::add);
        List<TextBlock> ordered = new ArrayList<>();
        for (var b : combined) ordered.add(new TextBlock(b.id(), b.pageNumber(), b.box(), b.text(), b.baselineY(), b.style(),
                ordered.size() + 1, b.textOffsetXmm(), b.textOffsetYmm(), b.advancesMm(), b.transform(), b.ocrWords()));
        List<String> conflicts = new ArrayList<>(original.conflicts());
        conflicts.add("完整框线内分格重新识别并补回文字；全部已有数字逐字核对。原全页跨框低置信候选记录："
                + String.join(" | ", superseded) + "。原扫描保留，分格结果和遗漏仍需人工复核。");
        return new TesseractOcrConverter.RecognitionResult(List.copyOf(ordered), confidence / totalWords, totalWords,
                original.imageEnhanced(), 0, List.copyOf(conflicts), true, false, true);
    }
}
