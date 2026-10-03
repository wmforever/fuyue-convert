package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.TextBlock;
import java.util.*;

/** TXT-only two-column inference. Ambiguous headings, short cells and crossing ink retain engine order. */
final class OcrReadingOrder {
    record Result(List<String> lines, boolean adjusted, boolean multipleColumns) {
        Result(List<String> lines, boolean adjusted) { this(lines, adjusted, false); }
    }
    private record Line(double y, String text, boolean narrative) { }
    private OcrReadingOrder() { }

    static Result arrange(List<TextBlock> blocks, double pageWidth, long deadline) {
        Result unchanged = new Result(blocks.stream().map(TextBlock::text).toList(), false);
        if (blocks.size() < 3 || blocks.size() > 500 || pageWidth <= 0 || System.nanoTime() >= deadline) return unchanged;
        List<TextBlock.OcrWord> words = new ArrayList<>();
        for (var block : blocks) {
            if (block.ocrWords().size() > 5000 - words.size() || System.nanoTime() >= deadline) return unchanged;
            words.addAll(block.ocrWords());
        }
        if (words.size() < 6) return unchanged;
        var sorted = new ArrayList<>(words); sorted.sort(Comparator.comparingDouble(w -> w.box().x()));
        double[] heights = words.stream().mapToDouble(w -> w.box().height()).sorted().toArray();
        double minimumGap = Math.max(pageWidth * .08, heights[heights.length / 2] * 4);
        double gap = 0, gutter = 0, right = sorted.get(0).box().right();
        int gutters = 0;
        for (var word : sorted) {
            double space = word.box().x() - right, middle = (word.box().x() + right) / 2;
            // Only one gutter is validated. Two significant internal gaps mean
            // three or more columns; sorting one combined side would interleave them.
            if (space >= minimumGap && right > 0 && word.box().x() < pageWidth && ++gutters > 1)
                return new Result(unchanged.lines(), false, true);
            if (space > gap && middle > pageWidth * .30 && middle < pageWidth * .70) { gap = space; gutter = middle; }
            right = Math.max(right, word.box().right());
        }
        if (gap < minimumGap) return unchanged;
        List<Line> left = new ArrayList<>(), next = new ArrayList<>();
        for (var block : blocks) {
            if (System.nanoTime() >= deadline || block.ocrWords().isEmpty()) return unchanged;
            // Separators may change; every non-whitespace character must come from engine words.
            if (!normalized(block.text()).equals(normalized(OcrDeskewSelection.join(block.ocrWords())))) return unchanged;
            List<TextBlock.OcrWord> a = new ArrayList<>(), b = new ArrayList<>();
            boolean crossed = false;
            for (var word : block.ocrWords()) {
                if (word.box().right() < gutter) { if (crossed) return unchanged; a.add(word); }
                else if (word.box().x() > gutter) { crossed = true; b.add(word); }
                else return unchanged;
            }
            if (!a.isEmpty() && !addNarrative(left, a) || !b.isEmpty() && !addNarrative(next, b)) return unchanged;
        }
        if (left.size() < 3 || next.size() < 3) return unchanged;
        if (left.stream().filter(Line::narrative).count() < Math.ceil(left.size() * .66)
                || next.stream().filter(Line::narrative).count() < Math.ceil(next.size() * .66)) return unchanged;
        left.sort(Comparator.comparingDouble(Line::y)); next.sort(Comparator.comparingDouble(Line::y));
        double start = Math.max(left.get(0).y(), next.get(0).y());
        double end = Math.min(left.get(left.size()-1).y(), next.get(next.size()-1).y());
        double extent = Math.max(left.get(left.size()-1).y(), next.get(next.size()-1).y())
                - Math.min(left.get(0).y(), next.get(0).y());
        if (end - start < extent * .60 || System.nanoTime() >= deadline) return unchanged;
        List<String> lines = new ArrayList<>(); left.forEach(l -> lines.add(l.text())); next.forEach(l -> lines.add(l.text()));
        // Character inventory is conserved, including decimal points/signs/leading zeros.
        if (!inventory(lines).equals(inventory(unchanged.lines())) || System.nanoTime() >= deadline) return unchanged;
        return new Result(List.copyOf(lines), !normalized(String.join("", lines)).equals(normalized(String.join("", unchanged.lines()))));
    }

    private static boolean addNarrative(List<Line> lines, List<TextBlock.OcrWord> words) {
        String text = OcrDeskewSelection.join(words);
        long letters = text.codePoints().filter(Character::isLetter).count();
        long cjk = text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        // Narrow numeric tables and label/value forms have insufficient independent prose.
        if (letters < 3) return false;
        boolean narrative = (letters >= 12 || cjk >= 6 && text.endsWith("。")) && (words.size() >= 3 || cjk >= 6);
        double y = words.stream().mapToDouble(w -> w.box().center().y()).average().orElse(0);
        lines.add(new Line(y, text, narrative)); return true;
    }
    private static String normalized(String text) { return text.replaceAll("\\s", ""); }
    private static Map<Integer, Integer> inventory(List<String> lines) {
        Map<Integer, Integer> result = new HashMap<>();
        for (String line : lines) line.codePoints().filter(c -> !Character.isWhitespace(c)).forEach(c -> result.merge(c, 1, Integer::sum));
        return result;
    }
}
