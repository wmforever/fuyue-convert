package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RapidOcrLayoutEngineTest {
    @TempDir Path temp;
    private TesseractOcrConverter.RecognitionResult parse(String body, int limit) throws Exception {
        Path json = temp.resolve("result.json"); Files.writeString(json, body);
        return RapidOcrLayoutEngine.parse(json, 2, new Rect(10, 20, 100, 80), 500, 400, limit);
    }
    private static String line(String text, String score, String quad) {
        return "{\"txt\":\"" + text + "\",\"score\":" + score + ",\"box\":" + quad + "}";
    }
    @Test void mapsOriginalSourceCoordinatesAndPreservesLiteralNumbersAndModelConfidence() throws Exception {
        var result = parse("[" + line("CPU -007.50% FTC662", "0.98", "[[100,100],[250,100],[250,150],[100,150]]") + "]", 10);
        var block = result.blocks().get(0);
        assertEquals(new Rect(30, 40, 30, 10), block.box());
        assertEquals("CPU -007.50% FTC662", block.text());
        assertEquals(block.text(), block.ocrWords().get(0).text());
        assertEquals(.98, result.confidence()); assertEquals(2, block.pageNumber());
        assertFalse(result.imageEnhanced()); assertFalse(result.partialRecovery());
    }
    @Test void invalidSchemaUnboundedEntriesAndOutsideCoordinatesDoNotBecomeSuccessfulOcr() {
        for (String value : new String[]{"[", "null", "{}", "[{\"txt\":5}]",
                "[" + line("FIELD", "1.1", "[[100,100],[250,100],[250,150],[100,150]]") + "]",
                "[" + line("FIELD", "0.9", "[[-1,100],[250,100],[250,150],[100,150]]") + "]"}) {
            var failure = assertThrows(ConversionFailureException.class, () -> parse(value, 10));
            assertEquals("OCR_OUTPUT_INVALID", failure.code());
        }
        String one = line("FIELD", "0.9", "[[100,100],[250,100],[250,150],[100,150]]");
        assertThrows(ConversionFailureException.class, () -> parse("[" + one + "," + one + "]", 1));
    }
    @Test void unsupportedRotationRetainsTheSourceInsteadOfDrawingUntransformedEditableText() throws Exception {
        var result = parse("[" + line("STAMP", "0.99", "[[100,100],[200,150],[180,190],[80,140]]") + "]", 10);
        assertTrue(result.blocks().isEmpty()); assertTrue(result.possibleTextOmission());
        assertTrue(result.conflicts().get(0).contains("1 个旋转"));
    }
    @Test void uncertainLinePredictionsRemainSourceInkInsteadOfEditableIncorrectDiagramLabels() throws Exception {
        String quad = "[[100,100],[250,100],[250,150],[100,150]]";
        var result = parse("[" + line("UNCERTAIN", "0.8499", quad) + "," + line("CPU 8", "0.85", quad) + "]", 10);
        assertEquals(1, result.blocks().size()); assertEquals("CPU 8", result.blocks().get(0).text());
        assertTrue(result.possibleTextOmission()); assertTrue(result.conflicts().get(0).contains("低于 0.85"));
    }
}
