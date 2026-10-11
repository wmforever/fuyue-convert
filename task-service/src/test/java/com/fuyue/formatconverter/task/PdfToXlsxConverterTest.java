package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfToXlsxConverterTest {
    @TempDir Path temp;
    private final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @Test
    void exportsVectorTableAsCellsAndMergedRegion() throws Exception {
        Path source = tablePdf();
        Path output = temp.resolve("table.xlsx");

        ConversionOutput result = new PdfToXlsxConverter().convert(
                new ConversionInput("table.pdf", "application/pdf", Files.size(source), source),
                temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(1, result.pageCount());
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("MERGED", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("LEFT", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("RIGHT", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(1, sheet.getNumMergedRegions());
            assertEquals(new CellRangeAddress(0, 0, 0, 1), sheet.getMergedRegion(0));
            assertEquals("正文（表格外文字）", sheet.getRow(4).getCell(0).getStringCellValue());
            assertTrue(sheet.getRow(5).getCell(0).getStringCellValue().contains("FOOTER"));
        }
    }

    @Test
    void keepsTextOnlyPdfInPageSheetInsteadOfProducingAnEmptyWorkbook() throws Exception {
        Path source = temp.resolve("text.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(500, 500));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                text(content, 60, 420, "BODY TEXT");
            }
            pdf.save(source.toFile());
        }

        Path output = temp.resolve("text.xlsx");
        new PdfToXlsxConverter().convert(new ConversionInput("text.pdf", "application/pdf",
                        Files.size(source), source), temp.resolve("text-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals("正文（未识别为表格）", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertTrue(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue().contains("BODY TEXT"));
        }
    }

    @Test
    void infersSafeNumbersDatesPercentagesAndFormulasWithoutExecutingOtherText() throws Exception {
        Path source = temp.resolve("typed.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(580, 500));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                float[] xs = {40, 120, 200, 280, 360, 440, 520};
                for (float x : xs) { content.moveTo(x, 200); content.lineTo(x, 350); }
                for (float y : new float[]{200, 250, 300, 350}) { content.moveTo(40, y); content.lineTo(520, y); }
                content.stroke();
                text(content, 50, 322, "00042");
                text(content, 130, 322, "12.50");
                text(content, 210, 322, "2026-10-11");
                text(content, 290, 322, "15%");
                text(content, 370, 322, "=B1+1");
                text(content, 450, 322, "=WEBSERVICE(A1)");
            }
            pdf.save(source.toFile());
        }

        Path output = temp.resolve("typed.xlsx");
        new PdfToXlsxConverter().convert(new ConversionInput("typed.pdf", "application/pdf",
                        Files.size(source), source), temp.resolve("typed-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            var row = workbook.getSheetAt(0).getRow(0);
            assertEquals(CellType.STRING, row.getCell(0).getCellType());
            assertEquals("00042", row.getCell(0).getStringCellValue());
            assertEquals(CellType.NUMERIC, row.getCell(1).getCellType());
            assertEquals(12.5, row.getCell(1).getNumericCellValue(), 0.0001);
            assertEquals(CellType.NUMERIC, row.getCell(2).getCellType());
            assertTrue(row.getCell(2).getCellStyle().getDataFormatString().contains("yyyy"));
            assertEquals(CellType.NUMERIC, row.getCell(3).getCellType());
            assertEquals(0.15, row.getCell(3).getNumericCellValue(), 0.0001);
            assertEquals(CellType.FORMULA, row.getCell(4).getCellType());
            assertEquals("B1+1", row.getCell(4).getCellFormula());
            assertEquals(CellType.STRING, row.getCell(5).getCellType());
            assertEquals("=WEBSERVICE(A1)", row.getCell(5).getStringCellValue());
        }
    }

    private Path tablePdf() throws Exception {
        Path source = temp.resolve("table.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(500, 500));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.moveTo(50, 250); content.lineTo(50, 350);
                content.moveTo(450, 250); content.lineTo(450, 350);
                content.moveTo(250, 250); content.lineTo(250, 300);
                for (float y : new float[]{250, 300, 350}) {
                    content.moveTo(50, y); content.lineTo(450, y);
                }
                content.stroke();
                text(content, 65, 322, "MERGED");
                text(content, 65, 272, "LEFT");
                text(content, 265, 272, "RIGHT");
                text(content, 60, 180, "FOOTER");
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private void text(PDPageContentStream content, float x, float y, String value) throws Exception {
        content.beginText(); content.setFont(font, 12); content.newLineAtOffset(x, y);
        content.showText(value); content.endText();
    }
}
