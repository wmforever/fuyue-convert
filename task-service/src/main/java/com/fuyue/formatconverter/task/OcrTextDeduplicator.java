package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.TextBlock;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Removes OCR/native duplicates only when both text and geometry agree. */
final class OcrTextDeduplicator {
    // Preserve numeric surfaces before punctuation-insensitive text matching.
    // Removing signs/separators or accepting a contained ID can otherwise erase
    // a distinct source value even when character boxes overlap perfectly.
    private static final Pattern NUMBER = Pattern.compile("[-+−－＋]?[.,，．٫]?\\p{Nd}+(?:[.,:/，．：／−－٫٬-]\\p{Nd}+)*");
    private OcrTextDeduplicator() { }

    static boolean duplicates(TextBlock candidate, List<TextBlock> existing) {
        String candidateText = normalize(candidate.text());
        if (candidateText.isEmpty()) return false;
        List<String> numbers = numbers(candidate.text());
        return existing.stream().anyMatch(text -> similar(candidateText, normalize(text.text()))
                && overlaps(candidate, text) && numbers.equals(numbers(text.text())));
    }

    /** Conflicting source values are retained, never selected by confidence. */
    static boolean numericConflict(TextBlock candidate, List<TextBlock> existing) {
        List<String> numbers = numbers(candidate.text());
        String context = letters(candidate.text());
        return existing.stream().anyMatch(text -> overlaps(candidate, text)
                && context.equals(letters(text.text()))
                && !numbers.equals(numbers(text.text())));
    }

    private static List<String> numbers(String text) {
        return NUMBER.matcher(text == null ? "" : text).results().map(java.util.regex.MatchResult::group).toList();
    }

    private static String letters(String text) {
        return normalize(text).codePoints().filter(Character::isLetter)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static boolean similar(String first, String second) {
        if (second.isEmpty()) return false;
        if (first.equals(second)) return true;
        int shorter = Math.min(first.length(), second.length());
        int longer = Math.max(first.length(), second.length());
        return shorter >= 4 && shorter / (double) longer >= 0.8d
                && (first.contains(second) || second.contains(first));
    }

    private static boolean overlaps(TextBlock first, TextBlock second) {
        double overlap = first.box().intersectionArea(second.box());
        double smallerArea = Math.max(0.01d, Math.min(first.box().width() * first.box().height(),
                second.box().width() * second.box().height()));
        return overlap / smallerArea > 0.7d;
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder normalized = new StringBuilder();
        value.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }
}
