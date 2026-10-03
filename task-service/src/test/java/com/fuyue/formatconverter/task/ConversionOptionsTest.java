package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        assertThrows(IllegalArgumentException.class, () -> ConversionOptions.fromRequest(
                null, null, null, null, null, null, null, null, null, 601));
    }

    @Test
    void normalizesImageRangesAndRoundTripsWorkerAndLegacyOptions() throws Exception {
        ConversionOptions options = imageOptions(" 4,2-3,2 ", "A4-AUTO", 0d);
        assertEquals(List.of(2, 3, 4), options.imagePageNumbers(5));
        assertEquals(ImagePdfPageSize.A4_AUTO, options.imagePdfPageSize());
        assertEquals(0d, options.imagePdfMarginMm());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(options, mapper.readValue(mapper.writeValueAsBytes(options), ConversionOptions.class));
        ConversionOptions legacy = mapper.readValue("{\"imageDpi\":300,\"splitPages\":\"all\"}", ConversionOptions.class);
        assertEquals("all", legacy.imagePages());
        assertEquals(ImagePdfPageSize.ORIGINAL, legacy.imagePdfPageSize());
        assertEquals(10d, legacy.imagePdfMarginMm());
    }

    @Test
    void rejectsInvalidImageRangesPaperAndMargins() {
        for (String range : List.of("0", "3-1", "1,", "1000001", "99999999999")) {
            assertThrows(IllegalArgumentException.class, () -> imageOptions(range, null, null));
        }
        assertThrows(ConversionFailureException.class, () -> imageOptions("1,4", null, null).imagePageNumbers(3));
        assertThrows(IllegalArgumentException.class, () -> imageOptions(null, "a3", null));
        for (double margin : new double[]{-1, 50.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> imageOptions(null, "a4-auto", margin));
        }
    }

    @Test
    void validatesAndRoundTripsSpreadsheetOptionsWithLegacyDefaults() throws Exception {
        ConversionOptions options = SpreadsheetPdfPreparationTest.options(" 3,1-2,2 ", true);
        assertEquals(List.of(1, 2, 3), options.spreadsheetSheetNumbers(4));
        assertEquals(true, options.spreadsheetFitWidth());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(options, mapper.readValue(mapper.writeValueAsBytes(options), ConversionOptions.class));
        ConversionOptions legacy = mapper.readValue("{}", ConversionOptions.class);
        assertEquals("all", legacy.spreadsheetSheets());
        assertFalse(legacy.spreadsheetFitWidth());
        for (String range : List.of("0", "3-1", "1,", "1000001", "999999999999")) {
            assertThrows(IllegalArgumentException.class, () -> SpreadsheetPdfPreparationTest.options(range, false));
        }
    }

    private ConversionOptions imageOptions(String pages, String paper, Double margin) {
        return ConversionOptions.fromRequest(null, null, null, null, null, null,
                null, null, null, null, pages, paper, margin);
    }
}
