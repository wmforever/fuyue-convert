package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrTextDeduplicatorTest {
    @Test
    void requiresSimilarTextAndSpatialOverlap() {
        TextBlock nativeText = text("Invoice No. 2026", 10, 10);

        assertTrue(OcrTextDeduplicator.duplicates(
                text("Invoice No 2026", 10.5, 10.2), List.of(nativeText)));
        assertFalse(OcrTextDeduplicator.duplicates(
                text("Invoice No 2026", 10, 50), List.of(nativeText)),
                "same words at a different position are real repeated content");
        assertFalse(OcrTextDeduplicator.duplicates(
                text("TOTAL 999", 10.5, 10.2), List.of(nativeText)),
                "different words in the same region must not be discarded");
    }

    private TextBlock text(String value, double x, double y) {
        return new TextBlock(value + x + y, 1, new Rect(x, y, 40, 8), value, y + 7,
                new FontStyle("Sans", 10, false, false, ColorValue.BLACK), 0);
    }
}
