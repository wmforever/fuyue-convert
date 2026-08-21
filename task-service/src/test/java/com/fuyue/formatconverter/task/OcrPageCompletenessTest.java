package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OcrPageCompletenessTest {
    @TempDir Path temp;

    @Test
    void pdfOcrRejectsMissingPageModelBeforePublishingPartialOutput() {
        DocumentModel incomplete = incompleteModel("broken.pdf");
        PdfOcrSupport support = new PdfOcrSupport(unavailableCapability());

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> support.recognizeMissingPages(temp.resolve("broken.pdf"), incomplete,
                        temp.resolve("pdf-work"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_PAGE_MISSING", failure.code());
    }

    @Test
    void ofdOcrRejectsMissingPageModelBeforePublishingPartialOutput() {
        DocumentModel incomplete = incompleteModel("broken.ofd");
        OfdOcrSupport support = new OfdOcrSupport(unavailableCapability());

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> support.recognizeRequiredPages(incomplete, temp.resolve("ofd-work"),
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_PAGE_MISSING", failure.code());
    }

    private DocumentModel incompleteModel(String name) {
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        return new DocumentModel(name, "test", 2, List.of(page), List.of());
    }

    private TesseractOcrConverter.Capability unavailableCapability() {
        return new TesseractOcrConverter.Capability(true, false, null, "OCR_ENGINE_UNAVAILABLE",
                "unavailable", null, "eng", Set.of(), null);
    }
}
