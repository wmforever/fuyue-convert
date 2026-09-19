package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

    @Test
    void pdfOcrMapsNoTextRasterCandidateLimitToStableFailure() throws Exception {
        Path source = temp.resolve("many-images.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(100, 100)));
            pdf.save(source.toFile());
        }
        PdfOcrSupport support = new PdfOcrSupport(unavailableCapability());

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> support.recognizeMissingPages(source, tooManyImageModel("many-images.pdf", false),
                        temp.resolve("pdf-limit-work"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_IMAGE_LIMIT_EXCEEDED", failure.code());
    }

    @Test
    void ofdOcrMapsRasterCandidateLimitBeforeUnavailableEngineFailure() {
        OfdOcrSupport support = new OfdOcrSupport(unavailableCapability());

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> support.recognizeRequiredPages(tooManyImageModel("many-images.ofd", true),
                        temp.resolve("ofd-limit-work"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_IMAGE_LIMIT_EXCEEDED", failure.code());
    }

    private DocumentModel incompleteModel(String name) {
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        return new DocumentModel(name, "test", 2, List.of(page), List.of());
    }

    private DocumentModel tooManyImageModel(String name, boolean ocrRequired) {
        List<ImageBlock> images = new java.util.ArrayList<>();
        for (int index = 0; index < 65; index++) {
            images.add(new ImageBlock("tile-" + index, 1, new Rect(0, 0, 100, 10),
                    "image/png", new byte[]{1}, "SCAN", index));
        }
        List<ConversionWarning> warnings = ocrRequired
                ? List.of(ConversionWarning.of(WarningCode.OCR_REQUIRED, "test", 1)) : List.of();
        PageModel page = new PageModel(1, new Rect(0, 0, 100, 100), List.of(), List.of(), images,
                List.of(), List.of(), warnings);
        return new DocumentModel(name, "test", 1, List.of(page), List.of());
    }

    private TesseractOcrConverter.Capability unavailableCapability() {
        return new TesseractOcrConverter.Capability(true, false, null, "OCR_ENGINE_UNAVAILABLE",
                "unavailable", null, "eng", Set.of(), null);
    }
}
