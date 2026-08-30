package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeFallbackPdfConverterTest {
    @TempDir Path temp;

    @Test
    void docxFallbackPreservesParagraphAndTableBodyOrderAndReportsPageCount() throws Exception {
        Path source = temp.resolve("ordered.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("FIRST-PARAGRAPH");
            document.createTable(1, 1).getRow(0).getCell(0).setText("MIDDLE-TABLE");
            document.createParagraph().createRun().setText("LAST-PARAGRAPH");
            try (var output = Files.newOutputStream(source)) { document.write(output); }
        }
        Path output = temp.resolve("ordered.pdf");

        ConversionOutput converted = new DocxToPdfConverter().convert(input(source, DocumentFormat.DOCX),
                temp.resolve("docx-work"), output, ParseLimits.defaults(), (stage, progress) -> { });

        assertNotNull(converted.pageCount());
        assertTrue(converted.pageCount() >= 1);
        assertTrue(converted.warnings().isEmpty());
        String text = pdfText(output).replaceAll("\\s+", " ");
        assertContainsInOrder(text, "FIRST-PARAGRAPH", "MIDDLE-TABLE", "LAST-PARAGRAPH");
    }

    @Test
    void xlsxFallbackUsesSavedFormulaResultAndReportsPageCount() throws Exception {
        Path source = temp.resolve("formula.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet("formula").createRow(0);
            row.createCell(0).setCellValue(19);
            row.createCell(1).setCellValue(23);
            var formula = row.createCell(2);
            formula.setCellFormula("A1+B1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            try (var output = Files.newOutputStream(source)) { workbook.write(output); }
        }
        Path output = temp.resolve("formula.pdf");

        ConversionOutput converted = new XlsxToPdfConverter().convert(input(source, DocumentFormat.XLSX),
                temp.resolve("xlsx-work"), output, ParseLimits.defaults(), (stage, progress) -> { });

        String text = pdfText(output).replaceAll("\\s+", " ");
        assertTrue(text.contains("42"), text);
        assertFalse(text.contains("A1+B1"), text);
        assertNotNull(converted.pageCount());
        assertTrue(converted.pageCount() >= 1);
        assertTrue(converted.warnings().stream()
                .anyMatch(warning -> warning.code() == WarningCode.FORMULA_RESULT_EXPORTED));
    }

    @Test
    void xlsxFallbackFormatsCachedFormulaDatesWith1904Windowing() throws Exception {
        Path source = temp.resolve("date-1904.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
            var formula = workbook.createSheet("date").createRow(0).createCell(0);
            formula.setCellFormula("0");
            formula.setCellValue(0d);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-MM-dd"));
            formula.setCellStyle(style);
            try (var output = Files.newOutputStream(source)) { workbook.write(output); }
        }

        Path output = temp.resolve("date-1904.pdf");
        new XlsxToPdfConverter().convert(input(source, DocumentFormat.XLSX), temp.resolve("date-work"),
                output, ParseLimits.defaults(), (stage, progress) -> { });

        String text = pdfText(output);
        assertTrue(text.contains("1904-01-01"), text);
        assertFalse(text.contains("1899-12-31"), text);
    }

    @Test
    void xlsxFallbackHonorsConfiguredPdfPageLimit() throws Exception {
        Path source = temp.resolve("too-many-rows.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("rows");
            for (int index = 0; index < 120; index++) {
                sheet.createRow(index).createCell(0).setCellValue("PAGE-LIMIT-ROW-" + index);
            }
            try (var output = Files.newOutputStream(source)) { workbook.write(output); }
        }
        ParseLimits onePage = new ParseLimits(5 * 1024 * 1024L, 10 * 1024 * 1024L,
                10 * 1024 * 1024L, 1_000, 100d, 1);

        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> new XlsxToPdfConverter().convert(input(source, DocumentFormat.XLSX),
                        temp.resolve("xlsx-limit-work"), temp.resolve("too-many-rows.pdf"), onePage,
                        (stage, progress) -> { }));

        assertEquals("PAGE_LIMIT_EXCEEDED", error.code());
    }

    @Test
    void docxFallbackHonorsConfiguredPdfPageLimit() throws Exception {
        Path source = temp.resolve("too-many-pages.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            for (int index = 0; index < 120; index++) {
                document.createParagraph().createRun().setText("PAGE-LIMIT-LINE-" + index);
            }
            try (var output = Files.newOutputStream(source)) { document.write(output); }
        }
        ParseLimits onePage = new ParseLimits(5 * 1024 * 1024L, 10 * 1024 * 1024L,
                10 * 1024 * 1024L, 1_000, 100d, 1);

        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> new DocxToPdfConverter().convert(input(source, DocumentFormat.DOCX),
                        temp.resolve("limit-work"), temp.resolve("too-many-pages.pdf"), onePage,
                        (stage, progress) -> { }));

        assertEquals("PAGE_LIMIT_EXCEEDED", error.code());
    }

    private ConversionInput input(Path source, DocumentFormat format) throws Exception {
        return new ConversionInput(source.getFileName().toString(), format.contentType(), Files.size(source), source);
    }

    private String pdfText(Path pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private void assertContainsInOrder(String value, String... expected) {
        int position = 0;
        for (String item : expected) {
            int found = value.indexOf(item, position);
            assertTrue(found >= position, "Missing or out of order: " + item + "\n" + value);
            position = found + item.length();
        }
    }
}
