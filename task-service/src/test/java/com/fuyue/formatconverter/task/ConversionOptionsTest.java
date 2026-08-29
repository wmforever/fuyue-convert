package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversionOptionsTest {
    @Test
    void suppliesSafeDefaults() {
        ConversionOptions options = ConversionOptions.defaults();
        assertEquals(PdfCompressionMode.LOSSLESS, options.compressionMode());
        assertEquals("CONFIDENTIAL", options.watermarkText());
        assertEquals(0.18d, options.watermarkOpacity());
        assertEquals(35d, options.watermarkAngle());
        assertEquals(WatermarkPosition.CENTER, options.watermarkPosition());
        assertFalse(options.watermarkTiled());
        assertEquals("all", options.watermarkPages());
        assertEquals("#969696", options.watermarkColor());
        assertEquals("all", options.splitPages());
    }

    @Test
    void parsesRequestValues() throws Exception {
        ConversionOptions options = ConversionOptions.fromRequest("strong", "内部资料", 0.3d,
                -25d, "bottom-right", true, "1,3-5", "#12abef");
        assertEquals(PdfCompressionMode.STRONG, options.compressionMode());
        assertEquals("内部资料", options.watermarkText());
        assertEquals(WatermarkPosition.BOTTOM_RIGHT, options.watermarkPosition());
        assertEquals("1,3-5", options.watermarkPages());
        assertEquals("#12ABEF", options.watermarkColor());

        ConversionOptions split = ConversionOptions.fromRequest(null, null, null, null,
                null, null, null, null, "2,4-5");
        assertEquals(List.of(2, 4, 5), split.splitPageNumbers(6));
    }

    @Test
    void rejectsUnsafeOrInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> ConversionOptions.fromRequest(
                "unknown", null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ConversionOptions.fromRequest(
                null, "bad\ntext", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ConversionOptions.fromRequest(
                null, null, 0.99d, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ConversionOptions.fromRequest(
                null, null, null, null, null, null, "0-2", null));
        assertThrows(IllegalArgumentException.class, () -> ConversionOptions.fromRequest(
                null, null, null, null, null, null, "5-3", null));
        assertThrows(IllegalArgumentException.class, () -> ConversionOptions.fromRequest(
                null, null, null, null, null, null, null, null, "3-1"));
        assertThrows(ConversionFailureException.class, () -> ConversionOptions.fromRequest(
                null, null, null, null, null, null, null, null, "8").splitPageNumbers(3));
    }
}
