package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.TextBlock;

import java.util.List;
import java.util.Locale;

/** Removes OCR/native duplicates only when both text and geometry agree. */
final class OcrTextDeduplicator {
    private OcrTextDeduplicator() { }

    static boolean duplicates(TextBlock candidate, List<TextBlock> existing) {
        String candidateText = normalize(candidate.text());
        if (candidateText.isEmpty()) return false;
        return existing.stream().anyMatch(text -> similar(candidateText, normalize(text.text()))
                && overlaps(candidate, text));
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
