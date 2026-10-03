package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class SpreadsheetPdfPreparationTest {
    @TempDir Path temp;

    static ConversionOptions options(String sheets, boolean width) {
        return ConversionOptions.fromRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, sheets, width);
    }

    @Test void selectsVisibleSheetsInWorkbookOrderAndRejectsHiddenOrInvalidSelection() throws Exception {
        var visible = List.of(true, false, true, false);
        assertEquals(List.of(0, 2), SpreadsheetPdfPreparation.select(visible, options("all", false)));
        assertEquals(List.of(0, 2), SpreadsheetPdfPreparation.select(visible, options("3,1,3", false)));
        assertEquals("SPREADSHEET_SHEET_HIDDEN", assertThrows(ConversionFailureException.class,
                () -> SpreadsheetPdfPreparation.select(visible, options("2", false))).code());
        assertEquals("SPREADSHEET_SHEET_RANGE_INVALID", assertThrows(ConversionFailureException.class,
                () -> SpreadsheetPdfPreparation.select(visible, options("5", false))).code());
        assertEquals("SPREADSHEET_NO_VISIBLE_SHEETS", assertThrows(ConversionFailureException.class,
                () -> SpreadsheetPdfPreparation.select(List.of(false), options("all", false))).code());
    }

    @Test void retainsOriginalAndOpaquePartsFormulasAndPrintAreasWhileChangingOnlySelectedPrintSettings() throws Exception {
        Path source = workbook();
        byte[] original = Files.readAllBytes(source);
        Path result = SpreadsheetPdfPreparation.prepare(source, temp.resolve("work"), options("3", true), ParseLimits.defaults());
        assertArrayEquals(original, Files.readAllBytes(source));
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(result))) {
            assertEquals(3, book.getNumberOfSheets());
            assertTrue(book.isSheetHidden(0));
            assertTrue(book.isSheetVeryHidden(1));
            assertFalse(book.isSheetHidden(2));
            assertEquals(2, book.getActiveSheetIndex());
            var selected = book.getSheetAt(2);
            assertTrue(selected.getFitToPage());
            assertEquals(1, selected.getPrintSetup().getFitWidth());
            assertEquals(0, selected.getPrintSetup().getFitHeight());
            assertTrue(selected.getPrintSetup().getLandscape());
            assertEquals(PrintSetup.A4_PAPERSIZE, selected.getPrintSetup().getPaperSize());
            assertEquals("Report!$A$1:$T$200", book.getPrintArea(2));
            assertEquals("Inputs!A1*2", selected.getRow(0).getCell(0).getCellFormula());
            assertEquals(84, selected.getRow(0).getCell(0).getNumericCellValue());
        }
        try (ZipFile before = new ZipFile(source.toFile()); ZipFile after = new ZipFile(result.toFile())) {
            assertEquals(before.size(), after.size());
            var entries = before.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (List.of("xl/workbook.xml", "xl/worksheets/sheet3.xml").contains(entry.getName())) continue;
                assertArrayEquals(before.getInputStream(entry).readAllBytes(),
                        after.getInputStream(after.getEntry(entry.getName())).readAllBytes(), entry.getName());
            }
        }
    }

    @Test void preservesDefaultInputAndHandlesWorksheetWithoutExistingPrintNodes() throws Exception {
        Path source = workbook();
        assertEquals(source, SpreadsheetPdfPreparation.prepare(source, temp, options("all", false), ParseLimits.defaults()));
        Path result = SpreadsheetPdfPreparation.prepare(source, temp.resolve("basic"), options("1", true), ParseLimits.defaults());
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(result))) {
            assertTrue(book.getSheetAt(0).getFitToPage());
            assertEquals(1, book.getSheetAt(0).getPrintSetup().getFitWidth());
            assertEquals(0, book.getSheetAt(0).getPrintSetup().getFitHeight());
            assertEquals(42, book.getSheetAt(1).getRow(0).getCell(0).getNumericCellValue());
        }
    }

    @Test void enforcesExpandedArchiveLimitsAndRemovesPartialCopy() throws Exception {
        Path source = workbook();
        Path work = temp.resolve("bounded");
        ParseLimits tiny = new ParseLimits(100_000, 2000, 2000, 100, 100, 10);
        assertThrows(Exception.class, () -> SpreadsheetPdfPreparation.prepare(source, work, options("3", true), tiny));
        assertFalse(Files.exists(work.resolve("spreadsheet-print.xlsx")));
    }

    @Test void rejectsXmlWithExternalEntities() throws Exception {
        Path source = temp.resolve("external.xlsx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write(("<!DOCTYPE workbook [<!ENTITY external SYSTEM 'file:///nonexistent'>]>"
                    + "<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'>&external;</workbook>").getBytes());
            zip.closeEntry();
        }
        assertThrows(Exception.class, () -> SpreadsheetPdfPreparation.prepare(source, temp.resolve("external"), options("1", true), ParseLimits.defaults()));
    }

    private Path workbook() throws Exception {
        Path source = temp.resolve("source.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            book.createSheet("Overview").createRow(0).createCell(0).setCellValue("OVERVIEW");
            book.createSheet("Inputs").createRow(0).createCell(0).setCellValue(42);
            book.setSheetVisibility(1, SheetVisibility.VERY_HIDDEN);
            var sheet = book.createSheet("Report");
            var formula = sheet.createRow(0).createCell(0);
            formula.setCellFormula("Inputs!A1*2");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setScale((short) 80);
            sheet.getPrintSetup().setFitHeight((short) 1);
            book.setPrintArea(2, 0, 19, 0, 199);
            try (var out = Files.newOutputStream(source)) { book.write(out); }
        }
        return source;
    }
}
