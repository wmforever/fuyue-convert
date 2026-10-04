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

    @Test void preservesEveryDifferentNumericSurfaceEvenWithMatchingTextAndGeometry() {
        for (String[] pair : new String[][] {
                {"Record 007930","Record 00793"}, {"Amount -048.65","Amount 048.65"},
                {"Amount +048.65","Amount 048.65"}, {"Amount 048.65","Amount 04865"},
                {"Amount .47","Amount 47"}, {"Amount 00048.65","Amount 048.65"},
                {"Date 2076-10-14","Date 20761014"}, {"Amount 1,048.65","Amount 1048.65"},
                {"Values 12 34","Values 1234"}, {"Amount −048.65","Amount -048.65"}}) {
            var a=text(pair[0],10,10);var b=text(pair[1],10,10);
            assertFalse(OcrTextDeduplicator.duplicates(a,List.of(b)),pair[0]+" versus "+pair[1]);
            assertFalse(OcrTextDeduplicator.duplicates(b,List.of(a)),"reverse comparison");
            assertTrue(OcrTextDeduplicator.numericConflict(a,List.of(b)),"source conflict must be visible");
        }
    }
    @Test void retainsWordFormattingToleranceOnlyWhenNumericTokensMatchExactly() {
        assertTrue(OcrTextDeduplicator.duplicates(text("Invoice No. 2026",10,10),List.of(text("Invoice No 2026",10,10))));
        assertTrue(OcrTextDeduplicator.duplicates(text("Values 12\n34",10,10),List.of(text("Values 12 34",10,10))));
        assertTrue(OcrTextDeduplicator.duplicates(text("编号 Record 00842",10,10),List.of(text("Record 00842",10,10))),
                "do not relax the original zero-novelty gate through duplicate bilingual prefixes");
        assertFalse(OcrTextDeduplicator.numericConflict(text("Amount 048.65",10,10),List.of(text("Amount 048.65",10,10))));
    }
    @Test void distinguishesConflictingValuesFromLegitimateRepeatedContentOrDifferentLabels() {
        assertTrue(OcrTextDeduplicator.numericConflict(text("Amount 048.65",10,10),List.of(text("Amount 048.66",10,10))));
        assertFalse(OcrTextDeduplicator.numericConflict(text("Amount 048.65",10,10),List.of(text("Amount 048.66",10,60))));
        assertFalse(OcrTextDeduplicator.numericConflict(text("Amount 048.65",10,10),List.of(text("Record 00793",10,10))));
        assertFalse(OcrTextDeduplicator.duplicates(text("AUDITX 2076",10,10),List.of(text("AUDIT 2076",10,10))));
    }

    private TextBlock text(String value, double x, double y) {
        return new TextBlock(value + x + y, 1, new Rect(x, y, 40, 8), value, y + 7,
                new FontStyle("Sans", 10, false, false, ColorValue.BLACK), 0);
    }
}
