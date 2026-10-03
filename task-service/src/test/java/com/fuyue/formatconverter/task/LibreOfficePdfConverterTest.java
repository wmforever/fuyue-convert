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

import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    void exportsSelectedWideSheetWithHiddenFormulaDependenciesAndUnlimitedVerticalPages() throws Exception {
        var discovered = LibreOfficeConverter.discover("");
        assumeTrue(discovered.isPresent(), "LibreOffice is not installed");
        Path source = temp.resolve("wide.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            book.createSheet("Overview").createRow(0).createCell(0).setCellValue("EXCLUDED-OVERVIEW");
            book.createSheet("Inputs").createRow(0).createCell(0).setCellValue(42);
            book.setSheetHidden(1, true);
            var sheet = book.createSheet("宽表");
            sheet.setDefaultColumnWidth(12);
            var header = sheet.createRow(0);
            for (int col = 0; col < 20; col++) header.createCell(col).setCellValue(String.format("COL%02d", col + 1));
            var formula = sheet.createRow(1).createCell(0);
            formula.setCellFormula("Inputs!A1*2");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            sheet.getRow(1).createCell(1).setCellValue(CJK_TEXT);
            for (int row = 2; row < 260; row++) sheet.createRow(row).createCell(0).setCellValue("ROW-" + row);
            sheet.getRow(259).createCell(19).setCellValue("LAST-ROW-LAST-COLUMN");
            sheet.setRepeatingRows(new org.apache.poi.ss.util.CellRangeAddress(0, 0, -1, -1));
            sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            book.setPrintArea(2, 0, 19, 0, 259);
            try (var output = Files.newOutputStream(source)) { book.write(output); }
        }
        byte[] original = Files.readAllBytes(source);
        var converter = new LibreOfficeConverter(DocumentFormat.XLSX, DocumentFormat.PDF, discovered.orElseThrow(),
                Duration.ofSeconds(45), "wide integration");
        Path nativePdf = temp.resolve("native.pdf"), fitPdf = temp.resolve("fit.pdf");
        var nativeResult = converter.convert(new ConversionInput("wide.xlsx", DocumentFormat.XLSX.contentType(),
                Files.size(source), source, SpreadsheetPdfPreparationTest.options("3", false)),
                temp.resolve("native-work"), nativePdf, ParseLimits.defaults(), (stage, percent) -> {});
        var fitResult = converter.convert(new ConversionInput("wide.xlsx", DocumentFormat.XLSX.contentType(),
                Files.size(source), source, SpreadsheetPdfPreparationTest.options("3", true)),
                temp.resolve("fit-work"), fitPdf, ParseLimits.defaults(), (stage, percent) -> {});
        assertTrue(fitResult.pageCount() > 1, "long sheet must retain vertical pagination");
        assertTrue(fitResult.pageCount() < nativeResult.pageCount(), "fit width must reduce horizontal pages");
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var pdf = Loader.loadPDF(fitPdf.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertFalse(text.contains("EXCLUDED-OVERVIEW"), text);
            assertTrue(text.contains("84"), text);
            assertTrue(text.contains(CJK_TEXT), text);
            assertTrue(text.contains("LAST-ROW-LAST-COLUMN"), text);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page); stripper.setEndPage(page);
                String pageText = stripper.getText(pdf);
                assertTrue(pageText.contains("COL01") && pageText.contains("COL20"), pageText);
            }
        }
    }

    @Test
    void windowsVersionProbeUsesConsoleLauncherWithoutChangingConversionBinary() throws Exception {
        Path exe = temp.resolve("soffice.exe"), console = temp.resolve("soffice.com");
        Files.writeString(exe, "exe"); Files.writeString(console, "console");
        assertEquals(console, LibreOfficeConverter.versionProbeBinary(exe, true));
        assertEquals(exe, LibreOfficeConverter.versionProbeBinary(exe, false));
        Files.delete(console);
        assertEquals(exe, LibreOfficeConverter.versionProbeBinary(exe, true));
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
