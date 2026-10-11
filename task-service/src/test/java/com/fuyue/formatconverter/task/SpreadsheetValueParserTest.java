package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpreadsheetValueParserTest {
    @Test
    void parsesGroupedNumbersAndChineseDatesConservatively() {
        SpreadsheetValueParser.ParsedValue grouped = SpreadsheetValueParser.parse("1,234.50");
        assertEquals(SpreadsheetValueParser.ValueKind.NUMBER, grouped.kind());
        assertEquals(1234.5d, grouped.number(), 0.0001d);

        SpreadsheetValueParser.ParsedValue date = SpreadsheetValueParser.parse("2026年10月11日");
        assertEquals(SpreadsheetValueParser.ValueKind.DATE, date.kind());
        assertEquals("2026-10-11", date.date().toString());
    }

    @Test
    void keepsIdentifiersMultilineTextAndUnsafeFormulasLiteral() {
        for (String value : new String[]{"00123", "-00123", "12\n34", "=WEBSERVICE(A1)", "=A1+SUM(B1)"}) {
            SpreadsheetValueParser.ParsedValue parsed = SpreadsheetValueParser.parse(value);
            assertEquals(SpreadsheetValueParser.ValueKind.TEXT, parsed.kind(), value);
            assertNull(parsed.value(), value);
        }
    }
}
