package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative spatial conservation; reliable numbers are never silently replaced. */
final class OcrDeskewSelection {
    private static final Pattern NUMBER = Pattern.compile("[-+−－＋]?[.,，．٫]?\\p{Nd}+(?:[.,:/，．：／−－٫٬-]\\p{Nd}+)*");
    private OcrDeskewSelection() { }

    static TesseractOcrConverter.RecognitionResult select(TesseractOcrConverter.RecognitionResult original,
            TesseractOcrConverter.RecognitionResult candidate, OcrDeskew.Prepared prepared,
            Rect physical, int sourceWidth, int sourceHeight, double minimumConfidence) {
        return select(original, candidate, prepared, physical, sourceWidth, sourceHeight, minimumConfidence, Long.MAX_VALUE);
    }

    static TesseractOcrConverter.RecognitionResult select(TesseractOcrConverter.RecognitionResult original,
            TesseractOcrConverter.RecognitionResult candidate, OcrDeskew.Prepared prepared,
            Rect physical, int sourceWidth, int sourceHeight, double minimumConfidence, long deadline) {
        if (System.nanoTime() >= deadline || (long) original.wordCount() * candidate.wordCount() > 2_000_000) return original;
        double sx = physical.width() / sourceWidth, sy = physical.height() / sourceHeight;
        List<TextBlock.OcrWord> mapped = new ArrayList<>();
        for (TextBlock line : candidate.blocks()) for (TextBlock.OcrWord word : line.ocrWords()) {
            if (System.nanoTime() >= deadline) return original;
            Rect bounds = prepared.originalBounds(word.box(), sx, sy, physical);
            if (!physical.contains(new Point(bounds.x(), bounds.y()), .01)
                    || !physical.contains(new Point(bounds.right(), bounds.bottom()), .01)) return original;
            mapped.add(new TextBlock.OcrWord(bounds, word.text(), word.confidence()));
        }
        if (mapped.isEmpty() || candidate.confidence() < Math.max(minimumConfidence, original.confidence() - .05)) return original;
        List<List<TextBlock.OcrWord>> previous = new ArrayList<>();
        for (int index = 0; index < mapped.size(); index++) previous.add(new ArrayList<>());
        int comparisons = 0;
        for (TextBlock line : original.blocks()) for (TextBlock.OcrWord word : line.ocrWords()) {
            if (word.confidence() < .85) continue;
            int match = -1;
            double overlap = .25;
            for (int index = 0; index < mapped.size(); index++) {
                if (++comparisons > 2_000_000 || ((index & 63) == 0 && System.nanoTime() >= deadline)) return original;
                double value = word.box().intersectionArea(mapped.get(index).box())
                        / Math.max(1e-9, word.box().width() * word.box().height());
                if (value > overlap) { match = index; overlap = value; }
            }
            if (match < 0) {
                // OCR can assign a short token an inflated box. Do not relax
                // the original overlap threshold: require exact reliable text,
                // a unique contained majority of the candidate, and an unused
                // candidate so distinct occurrences cannot collapse together.
                int exact = -1;
                for (int index = 0; index < mapped.size(); index++) {
                    if (++comparisons > 2_000_000 || ((index & 63) == 0 && System.nanoTime() >= deadline)) return original;
                    var next = mapped.get(index);
                    if (next.confidence() < .85 || !normalized(word.text()).equals(normalized(next.text()))) continue;
                    double coverage = word.box().intersectionArea(next.box())
                            / Math.max(1e-9, next.box().width() * next.box().height());
                    if (coverage < .50 || !word.box().contains(next.box().center(), 0)) continue;
                    if (exact >= 0 || !previous.get(index).isEmpty()) return original;
                    exact = index;
                }
                match = exact;
            }
            if (match < 0) return original; // Reliable source content cannot disappear in recovery.
            previous.get(match).add(word);
        }
        List<String> conflicts = new ArrayList<>();
        double radians = Math.toRadians(prepared.degrees());
        for (int index = 0; index < mapped.size(); index++) {
            var old = previous.get(index);
            if (old.isEmpty()) continue;
            old.sort(Comparator.comparingDouble(word -> word.box().center().x() * Math.cos(radians)
                    + word.box().center().y() * Math.sin(radians)));
            var next = mapped.get(index);
            String prior = join(old);
            // Extract within each original word: joining two numeric tokens first
            // can manufacture a new amount ("12" + "34" -> "1234").
            List<String> numbers = old.stream().flatMap(word -> NUMBER.matcher(word.text()).results())
                    .map(result -> result.group()).toList();
            List<String> replacements = NUMBER.matcher(next.text()).results().map(result -> result.group()).toList();
            if (!numbers.isEmpty() && (!numbers.equals(replacements) || !prior.equals(next.text()))) {
                if (numbers.size() != replacements.size() || old.size() != 1) return original;
                // Preserve the complete reliable token, including decimal/sign,
                // currency, percent and grouping context. Never splice regex
                // fragments into candidate text to create a third numeric value.
                conflicts.add("数字原结果“" + prior + "”与校正候选“" + next.text() + "”冲突；保留原数字。");
                mapped.set(index, new TextBlock.OcrWord(next.box(), old.get(0).text(),
                        Math.min(next.confidence(), old.stream().mapToDouble(TextBlock.OcrWord::confidence).min().orElse(next.confidence()))));
            } else if (!normalized(next.text()).contains(normalized(prior))) {
                conflicts.add("文字原结果“" + prior + "”与校正候选“" + next.text() + "”不同；请对照原图复核。");
            }
            if (conflicts.size() > 32) return original;
        }
        List<TextBlock> lines = new ArrayList<>();
        int offset = 0;
        for (TextBlock line : candidate.blocks()) {
            var words = List.copyOf(mapped.subList(offset, offset + line.ocrWords().size()));
            offset += words.size();
            Rect bounds = words.get(0).box();
            for (int index = 1; index < words.size(); index++) bounds = bounds.union(words.get(index).box());
            String text = join(words);
            lines.add(new TextBlock(line.id(), line.pageNumber(), bounds, text, bounds.bottom(), line.style(),
                    line.zOrder(), 0, 0, List.of(), Transform2D.IDENTITY, words));
        }
        int before = original.blocks().stream().mapToInt(line -> normalized(line.text()).length()).sum();
        int after = lines.stream().mapToInt(line -> normalized(line.text()).length()).sum();
        if (after < before * .90 || System.nanoTime() >= deadline) return original;
        double confidence = mapped.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0);
        return new TesseractOcrConverter.RecognitionResult(lines, confidence, mapped.size(), original.imageEnhanced(),
                prepared.degrees(), conflicts);
    }

    static String join(List<TextBlock.OcrWord> words) {
        StringBuilder text = new StringBuilder();
        for (var word : words) {
            if (!text.isEmpty() && !word.text().isEmpty()
                    && TesseractOcrConverter.wordSeparator(text.codePointBefore(text.length()), word.text().codePointAt(0))) text.append(' ');
            text.append(word.text());
        }
        return text.toString();
    }
    private static String normalized(String text) { return text.replaceAll("\\s", "").toLowerCase(Locale.ROOT); }
}
