package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LibreOfficeUofConverterTest {
    @TempDir Path temp;

    @Test
    void exportsRealUofXmlAndLibreOfficeCanOpenItBackWithParagraphAndTableText() throws Exception {
        var discovered = LibreOfficeConverter.discover("");
        assumeTrue(discovered.isPresent(), "LibreOffice is not installed");
        Path source = temp.resolve("source.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("UOF 往返正文 2026");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("左侧单元格");
            table.getRow(0).getCell(1).setText("右侧单元格");
            try (var out = Files.newOutputStream(source)) { document.write(out); }
        }
        Path uof = temp.resolve("source.uof");
        var export = new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.UOF,
                discovered.orElseThrow(), Duration.ofSeconds(30), "test", "uof:UOF text");

        ConversionOutput result = export.convert(input(source, DocumentFormat.DOCX.contentType()),
                temp.resolve("export"), uof, ParseLimits.defaults(), (stage, percent) -> { });

        String prefix = Files.readString(uof, StandardCharsets.UTF_8).substring(0, 256);
        assertTrue(prefix.contains("<uof:UOF"), prefix);
        assertTrue(prefix.contains("schemas.uof.org"), prefix);
        assertEquals(QualityLevel.EXPERIMENTAL, export.route().qualityLevel());
        assertEquals(ConversionStrategy.COMPATIBILITY, export.route().strategy());
        assertTrue(result.outputName().endsWith(".uof"));

        Path roundTrip = temp.resolve("roundtrip.docx");
        var reopen = new LibreOfficeConverter(DocumentFormat.UOF, DocumentFormat.DOCX,
                discovered.orElseThrow(), Duration.ofSeconds(30), "test");
        reopen.convert(input(uof, DocumentFormat.UOF.contentType()), temp.resolve("reopen"), roundTrip,
                ParseLimits.defaults(), (stage, percent) -> { });
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(roundTrip))) {
            String paragraphs = document.getParagraphs().stream().map(p -> p.getText())
                    .reduce("", (left, right) -> left + right);
            assertTrue(paragraphs.contains("UOF 往返正文 2026"), paragraphs);
            assertEquals("左侧单元格", document.getTables().get(0).getRow(0).getCell(0).getText());
            assertEquals("右侧单元格", document.getTables().get(0).getRow(0).getCell(1).getText());
        }
    }

    private ConversionInput input(Path source, String contentType) throws Exception {
        return new ConversionInput(source.getFileName().toString(), contentType, Files.size(source), source);
    }
}
