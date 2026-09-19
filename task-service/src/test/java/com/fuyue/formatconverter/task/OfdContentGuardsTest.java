package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.parser.SafeOfdPackage;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfdContentGuardsTest {
    @TempDir Path temp;

    @Test
    void editableTextAndSpreadsheetRoutesRejectFailedImageExtraction() throws Exception {
        Path source = temp.resolve("image-failure.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(100d, 60d));
        }
        OfdParser parser = failedImageParser();
        SafeOfdExtractor extractor = new SafeOfdExtractor();
        PageLayoutAnalyzer analyzer = new PageLayoutAnalyzer();

        assertImageFailure(() -> new OfdToDocxConverter(extractor, parser, analyzer, new PoiDocxRenderer())
                .convert(input(source), temp.resolve("docx-work"), temp.resolve("failed.docx"),
                        ParseLimits.defaults(), (stage, percent) -> { }));
        assertImageFailure(() -> new OfdToTextConverter(extractor, parser, analyzer)
                .convert(input(source), temp.resolve("text-work"), temp.resolve("failed.txt"),
                        ParseLimits.defaults(), (stage, percent) -> { }));
        assertImageFailure(() -> new OfdToXlsxConverter(extractor, parser, analyzer)
                .convert(input(source), temp.resolve("xlsx-work"), temp.resolve("failed.xlsx"),
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertFalse(Files.exists(temp.resolve("failed.docx")));
        assertFalse(Files.exists(temp.resolve("failed.txt")));
        assertFalse(Files.exists(temp.resolve("failed.xlsx")));
    }

    private void assertImageFailure(org.junit.jupiter.api.function.Executable conversion) {
        ConversionFailureException failure = assertThrows(ConversionFailureException.class, conversion);
        assertEquals("OFD_IMAGE_EXTRACTION_FAILED", failure.code());
    }

    private OfdParser failedImageParser() {
        return new OfdParser() {
            @Override
            public DocumentModel parse(SafeOfdPackage source, String displayName, ParseLimits limits) {
                PageModel page = new PageModel(1, new Rect(0, 0, 100, 60), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(ConversionWarning.of(WarningCode.IMAGE_EXTRACTION_FAILED,
                                "synthetic image extraction failure", 1)));
                return new DocumentModel(displayName, name(), 1, List.of(page), List.of());
            }

            @Override public String name() { return "test-parser"; }
        };
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/ofd", Files.size(source), source);
    }
}
