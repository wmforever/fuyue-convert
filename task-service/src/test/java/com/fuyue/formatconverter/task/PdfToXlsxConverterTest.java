package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.model.CellModel;
import com.fuyue.formatconverter.model.MergeCellModel;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ParagraphModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TableModel;
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
import java.util.List;

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

    @Test
    void appliesExplicitMergeMetadataWhenCellsAreAtomic() {
        CellModel left = new CellModel(0, 0, 1, 1, new Rect(0, 0, 10, 10),
                List.of(), null, null, null, null, null,
                ParagraphModel.Alignment.LEFT, CellModel.VerticalAlignment.CENTER);
        CellModel right = new CellModel(0, 1, 1, 1, new Rect(10, 0, 10, 10),
                List.of(), null, null, null, null, null,
                ParagraphModel.Alignment.LEFT, CellModel.VerticalAlignment.CENTER);
        TableModel table = new TableModel("metadata-merge", 1, new Rect(0, 0, 20, 10),
                List.of(0d, 10d, 20d), List.of(0d, 10d), List.of(left, right),
                List.of(new MergeCellModel(0, 0, 1, 2, 0.95d)), 0.95d, List.of());

        assertEquals(List.of(new CellRangeAddress(7, 7, 0, 1)),
                PdfToXlsxConverter.mergedRegions(table, 7));
    }

    @Test
    void detectsConservativeCrossPageContinuationAndRepeatedHeader() {
        PageModel first = pageWithTable(1, 75, "列名");
        PageModel second = pageWithTable(2, 0, "列名");

        List<PdfToXlsxConverter.TableContinuation> continuations =
                PdfToXlsxConverter.detectContinuations(List.of(first, second));

        assertEquals(1, continuations.size());
        assertEquals(1, continuations.get(0).fromPage());
        assertEquals(2, continuations.get(0).toPage());
        assertTrue(continuations.get(0).repeatedHeader());
    }

    @Test
    void buildsCrossPageSummaryChainWithoutGuessingUnrelatedPages() {
        PageModel first = pageWithTable(1, 75, "列名");
        PageModel second = pageWithTable(2, 0, "列名");
        PageModel unrelated = pageWithTable(4, 0, "列名");

        List<CrossPageTableSupport.TableChain> chains = CrossPageTableSupport.chains(
                List.of(first, second, unrelated));

        assertEquals(1, chains.size());
        assertEquals(2, chains.get(0).segments().size());
        assertEquals(1, chains.get(0).firstPage());
        assertEquals(2, chains.get(0).lastPage());
        assertTrue(chains.get(0).segments().get(1).repeatedHeader());
    }

    private PageModel pageWithTable(int pageNumber, double tableY, String header) {
        CellModel cell = new CellModel(0, 0, 1, 1, new Rect(10, tableY, 40, 10),
                List.of(new ParagraphModel(new Rect(10, tableY, 40, 10),
                        List.of(new com.fuyue.formatconverter.model.TextBlock("header-" + pageNumber,
                                pageNumber, new Rect(12, tableY + 2, 20, 5), header, tableY + 7,
                                FontStyle.defaults(), 0)), ParagraphModel.Alignment.LEFT, 0)),
                null, null, null, null, null, ParagraphModel.Alignment.LEFT,
                CellModel.VerticalAlignment.CENTER);
        TableModel table = new TableModel("table-" + pageNumber, pageNumber,
                new Rect(10, tableY, 80, 25), List.of(10d, 50d, 90d),
                List.of(tableY, tableY + 10, tableY + 25), List.of(cell), List.of(),
                0.95d, List.of());
        return new PageModel(pageNumber, new Rect(0, 0, 100, 100), List.of(), List.of(), List.of(),
                List.of(), List.of(table), List.of());
    }

    @Test
    void ignoresInvalidAndOverlappingMergeMetadata() {
        TableModel table = new TableModel("invalid-merges", 1, new Rect(0, 0, 30, 20),
                List.of(0d, 10d, 20d, 30d), List.of(0d, 10d, 20d), List.of(),
                List.of(new MergeCellModel(0, 0, 1, 2, 0.9d),
                        new MergeCellModel(0, 1, 1, 2, 0.9d),
                        new MergeCellModel(0, 2, 0, 2, 0.9d)), 0.9d, List.of());

        assertEquals(List.of(new CellRangeAddress(0, 0, 0, 1)),
                PdfToXlsxConverter.mergedRegions(table, 0));
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
