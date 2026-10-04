package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.TextBlock;

import java.util.List;
import java.util.ArrayList;
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
        String candidateText = normalize(candidate.text());
        return existing.stream().anyMatch(text -> overlaps(candidate, text)
                && (context.equals(letters(text.text())) || similar(candidateText, normalize(text.text())))
                && !numbers.equals(numbers(text.text())));
    }

    private static List<String> numbers(String text) {
        String value = text == null ? "" : text;
        var matcher = NUMBER.matcher(value);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            int left = matcher.start(), right = matcher.end();
            // Keep literal accounting/currency/unit markers, including a sign
            // separated from its digits. Whitespace alone never joins numbers.
            while (left > 0) {
                int marker = left;
                while (marker > 0 && numericSpace(value.codePointBefore(marker))) {
                    marker -= Character.charCount(value.codePointBefore(marker));
                }
                if (marker == 0 || !numericPrefix(value.codePointBefore(marker))) break;
                left = marker - Character.charCount(value.codePointBefore(marker));
            }
            while (right < value.length()) {
                int marker = right;
                boolean sameLine = true;
                while (marker < value.length() && numericSpace(value.codePointAt(marker))) {
                    sameLine &= !numericLineBreak(value.codePointAt(marker));
                    marker += Character.charCount(value.codePointAt(marker));
                }
                if (marker == value.length()) break;
                int c = value.codePointAt(marker);
                // A terminal accounting sign is part of the literal amount.
                // A range/list/field separator followed by more content is not.
                // Never borrow a sign from the next logical line.
                if (!numericSuffix(c) && !(sameLine && numericSign(c)
                        && terminalNumericSign(value, marker))) break;
                right = marker + Character.charCount(value.codePointAt(marker));
            }
            result.add(value.substring(left, right).codePoints().filter(c -> !numericSpace(c))
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString());
        }
        return result;
    }

    private static boolean numericPrefix(int c) {
        return Character.getType(c) == Character.CURRENCY_SYMBOL || c == '(' || c == '（'
                || c == '+' || c == '-' || c == '−' || c == '－' || c == '＋';
    }

    private static boolean numericSuffix(int c) {
        return Character.getType(c) == Character.CURRENCY_SYMBOL || c == ')' || c == '）'
                || c == '%' || c == '％' || c == '‰' || c == '‱' || c == '٪' || c == '﹪';
    }

    private static boolean numericSpace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean numericSign(int c) {
        return c == '+' || c == '-' || c == '−' || c == '－' || c == '＋';
    }

    private static boolean numericLineBreak(int c) {
        return c == '\n' || c == '\r' || c == '\f' || c == '\u000b'
                || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private static boolean terminalNumericSign(String value, int marker) {
        int after = marker + Character.charCount(value.codePointAt(marker));
        while (after < value.length()) {
            int c = value.codePointAt(after);
            if (!numericSpace(c) && !numericSuffix(c)) return false;
            after += Character.charCount(c);
        }
        return true;
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
