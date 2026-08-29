package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void parsesRequestValues() {
        ConversionOptions options = ConversionOptions.fromRequest("strong", "内部资料", 0.3d,
                -25d, "bottom-right", true, "1,3-5", "#12abef");
        assertEquals(PdfCompressionMode.STRONG, options.compressionMode());
        assertEquals("内部资料", options.watermarkText());
        assertEquals(WatermarkPosition.BOTTOM_RIGHT, options.watermarkPosition());
        assertEquals("1,3-5", options.watermarkPages());
        assertEquals("#12ABEF", options.watermarkColor());
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
    }
}
