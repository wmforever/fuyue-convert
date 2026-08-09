package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LibreOfficePdfConverterTest {
    private static final String CJK_TEXT = "跨平台中文";
    @TempDir Path temp;

    @Test
    void convertsDocxXlsxAndPptxToPdfWithReadableCjkAndPageCounts() throws Exception {
        var discovered = LibreOfficeConverter.discover("");
        assumeTrue(discovered.isPresent(), "LibreOffice is not installed");
        Path binary = discovered.orElseThrow();
        assertTrue(LibreOfficeConverter.version(binary).isPresent());

        verifyPdf(binary, createDocx(), DocumentFormat.DOCX, "DOCX-CJK");
        verifyPdf(binary, createXlsx(), DocumentFormat.XLSX, "XLSX-CJK");
        verifyPdf(binary, createPptx(), DocumentFormat.PPTX, "PPTX-CJK");
    }

    private void verifyPdf(Path binary, Path source, DocumentFormat sourceFormat, String marker) throws Exception {
        Path output = temp.resolve(marker.toLowerCase() + ".pdf");
        var converter = new LibreOfficeConverter(sourceFormat, DocumentFormat.PDF, binary,
                Duration.ofSeconds(45), "integration test");

        ConversionOutput converted = converter.convert(input(source, sourceFormat),
                temp.resolve(marker.toLowerCase() + "-work"), output, ParseLimits.defaults(),
                (stage, percent) -> { });

        assertNotNull(converted.pageCount());
        assertTrue(converted.pageCount() >= 1);
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(pdf.getNumberOfPages(), converted.pageCount());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains(marker), text);
            assertTrue(text.contains(CJK_TEXT), text);
        }
    }

    private Path createDocx() throws Exception {
        Path source = temp.resolve("office-source.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("DOCX-CJK " + CJK_TEXT);
            try (var output = Files.newOutputStream(source)) { document.write(output); }
        }
        return source;
    }

    private Path createXlsx() throws Exception {
        Path source = temp.resolve("office-source.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("跨平台").createRow(0).createCell(0)
                    .setCellValue("XLSX-CJK " + CJK_TEXT);
            try (var output = Files.newOutputStream(source)) { workbook.write(output); }
        }
        return source;
    }

    private Path createPptx() throws Exception {
        Path source = temp.resolve("office-source.pptx");
        try (XMLSlideShow slides = new XMLSlideShow()) {
            var text = slides.createSlide().createTextBox();
            text.setAnchor(new Rectangle2D.Double(40, 40, 600, 100));
            text.setText("PPTX-CJK " + CJK_TEXT);
            try (var output = Files.newOutputStream(source)) { slides.write(output); }
        }
        return source;
    }

    private ConversionInput input(Path source, DocumentFormat format) throws Exception {
        return new ConversionInput(source.getFileName().toString(), format.contentType(), Files.size(source), source);
    }
}
