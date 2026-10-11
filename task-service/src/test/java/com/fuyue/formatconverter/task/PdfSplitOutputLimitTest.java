package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfSplitOutputLimitTest {
    @TempDir Path temp;

    @Test
    void rejectsOversizedPageWhileSerializingAndRemovesPartialZip() throws Exception {
        Path source = temp.resolve("source.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(source.toFile());
        }
        Path output = temp.resolve("pages.zip");
        ParseLimits limits = new ParseLimits(1024 * 1024, 1024 * 1024, 1, 10, 100, 10);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new PdfSplitConverter().convert(
                        new ConversionInput("source.pdf", "application/pdf", Files.size(source), source),
                        temp.resolve("work"), output, limits, (stage, progress) -> { }));

        assertEquals("OUTPUT_LIMIT_EXCEEDED", failure.code());
        assertFalse(Files.exists(output));
    }
}
