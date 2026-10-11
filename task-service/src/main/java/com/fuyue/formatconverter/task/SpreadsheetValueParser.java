package com.fuyue.formatconverter.task;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative parser for values written to generated spreadsheets.
 *
 * <p>Only unambiguous, bounded forms are typed. Leading-zero identifiers,
 * multiline text, unsafe formulas and malformed dates remain literal text.
 * Formula recognition is deliberately allow-listed to avoid turning document
 * content into executable spreadsheet functions.</p>
 */
final class SpreadsheetValueParser {
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:0|[1-9]\\d{0,14})(?:\\.\\d{1,8})?%?");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("[+-]?(?:0|[1-9]\\d{0,2}(?:,\\d{3})+)(?:\\.\\d{1,8})?%?");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern CHINESE_DATE = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern SAFE_FORMULA = Pattern.compile(
            "=(?i:(SUM|AVERAGE|MIN|MAX|COUNT|COUNTA))\\(([A-Z]{1,3}\\d+(?::[A-Z]{1,3}\\d+)?)(?:,[A-Z]{1,3}\\d+(?::[A-Z]{1,3}\\d+)?)*\\)");
    private static final Pattern SAFE_ARITHMETIC_FORMULA = Pattern.compile(
            "=(?:[A-Z]{1,3}\\d+|(?:0|[1-9]\\d{0,14})(?:\\.\\d{1,8})?)(?:[+\\-*/](?:[A-Z]{1,3}\\d+|(?:0|[1-9]\\d{0,14})(?:\\.\\d{1,8})?))+");

    private SpreadsheetValueParser() { }

    static ParsedValue parse(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("\n") || raw.contains("\r")) {
            return ParsedValue.text();
        }
        String value = raw.strip();
        Matcher formula = SAFE_FORMULA.matcher(value);
        Matcher arithmetic = SAFE_ARITHMETIC_FORMULA.matcher(value);
        if (formula.matches() || arithmetic.matches()) {
            return new ParsedValue(ValueKind.FORMULA, value.substring(1), 0, null);
        }
        LocalDate date = parseDate(value);
        if (date != null) return new ParsedValue(ValueKind.DATE, null, 0, date);
        boolean percent = value.endsWith("%");
        String numeric = percent ? value.substring(0, value.length() - 1) : value;
        if (!NUMBER.matcher(value).matches() && !GROUPED_NUMBER.matcher(value).matches()) {
            return ParsedValue.text();
        }
        if (numeric.startsWith("+") || numeric.startsWith("-")) {
            String digits = numeric.substring(1);
            if (digits.length() > 1 && digits.startsWith("0") && !digits.startsWith("0.")) {
                return ParsedValue.text();
            }
        } else if (numeric.length() > 1 && numeric.startsWith("0") && !numeric.startsWith("0.")) {
            return ParsedValue.text();
        }
        try {
            double number = new BigDecimal(numeric.replace(",", "")).doubleValue();
            if (!Double.isFinite(number)) return ParsedValue.text();
            return new ParsedValue(percent ? ValueKind.PERCENT : ValueKind.NUMBER, null,
                    percent ? number / 100d : number, null);
        } catch (NumberFormatException ignored) {
            return ParsedValue.text();
        }
    }

    private static LocalDate parseDate(String value) {
        Matcher matcher = ISO_DATE.matcher(value);
        if (!matcher.matches()) matcher = CHINESE_DATE.matcher(value);
        if (!matcher.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    enum ValueKind { TEXT, NUMBER, PERCENT, DATE, FORMULA }

    record ParsedValue(ValueKind kind, String value, double number, LocalDate date) {
        static ParsedValue text() { return new ParsedValue(ValueKind.TEXT, null, 0, null); }
    }
}
