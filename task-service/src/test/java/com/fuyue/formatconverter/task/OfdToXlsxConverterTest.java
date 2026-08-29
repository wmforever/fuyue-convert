package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TableModel;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;
import org.ofdrw.layout.element.Img;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.ofdrw.layout.element.canvas.Canvas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfdToXlsxConverterTest {
    @TempDir Path temp;

    @Test
    void exportsPageTablesAsSheetsCellsAndMergedRegions() throws Exception {
        Path source = temp.resolve("tables.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(mergedTablePage("合并标题", "左值", "右值"));
            document.addVPage(singleCellTablePage("第二页数据"));
        }
        Path output = temp.resolve("tables.xlsx");

        ConversionOutput converted = converter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(2, converted.pageCount());
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("第1页", workbook.getSheetName(0));
            assertEquals("第2页", workbook.getSheetName(1));
            var first = workbook.getSheetAt(0);
            assertEquals("合并标题", first.getRow(0).getCell(0).getStringCellValue());
            assertEquals("左值", first.getRow(1).getCell(0).getStringCellValue());
            assertEquals("右值", first.getRow(1).getCell(1).getStringCellValue());
            assertEquals(1, first.getNumMergedRegions());
            assertEquals(new CellRangeAddress(0, 0, 0, 1), first.getMergedRegion(0));
            assertEquals("第二页数据", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void rejectsTextOnlyOfdInsteadOfProducingFakeSpreadsheet() throws Exception {
        Path source = temp.resolve("no-table.ofd");
        Paragraph paragraph = paragraph("只有正文", 10, 10, 70, 10);
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(100d, 50d).add(paragraph));
        }

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("no-table-work"),
                        temp.resolve("no-table.xlsx"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("NO_TABLE_FOUND", failure.code());
        assertTrue(Files.notExists(temp.resolve("no-table.xlsx")));
    }

    @Test
    void rejectsScannedOfdWithOcrRequiredInsteadOfProducingEmptyWorkbook() throws Exception {
        Path png = temp.resolve("scan.png");
        BufferedImage raster = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.drawString("scanned table", 200, 200);
        graphics.dispose();
        ImageIO.write(raster, "png", png.toFile());
        Img image = new Img(120d, 80d, png);
        image.setPosition(Position.Absolute).setBox(0d, 0d, 120d, 80d);
        Path source = temp.resolve("scan.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(120d, 80d).add(image));
        }
        Path output = temp.resolve("scan.xlsx");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("scan-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_REQUIRED", failure.code());
        assertTrue(failure.getMessage().contains("第 1 页"));
        assertTrue(Files.notExists(output));
    }

    @Test
    void reportsNoTableForTextLayerWithFidelityImageInsteadOfClaimingOcrIsMissing() throws Exception {
        Path png = temp.resolve("fidelity-layer.png");
        BufferedImage raster = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
        graphics.dispose();
        ImageIO.write(raster, "png", png.toFile());
        Img image = new Img(120d, 80d, png);
        image.setPosition(Position.Absolute).setBox(0d, 0d, 120d, 80d);
        Path source = temp.resolve("text-and-image.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(120d, 80d)
                    .add(image)
                    .add(paragraph("可检索文字层", 10, 10, 80, 10)));
        }

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("text-and-image-work"),
                        temp.resolve("text-and-image.xlsx"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("NO_TABLE_FOUND", failure.code());
    }

    @Test
    void excludesLowConfidenceGridCandidatesFromDataExport() {
        TableModel candidate = new TableModel("candidate", 1, new Rect(10, 10, 20, 10),
                List.of(10d, 30d), List.of(10d, 20d), List.of(), List.of(), 0.74d, List.of());
        PageModel page = new PageModel(1, new Rect(0, 0, 100, 60), List.of(), List.of(), List.of(),
                List.of(), List.of(candidate), List.of());

        assertTrue(OfdToXlsxConverter.retainReliableTables(page).tables().isEmpty());
    }

    private VirtualPage mergedTablePage(String title, String left, String right) {
        Canvas grid = new Canvas(80d, 20d).setDrawer(context -> context.beginPath()
                .moveTo(0, 0).lineTo(80, 0).moveTo(0, 10).lineTo(80, 10)
                .moveTo(0, 20).lineTo(80, 20).moveTo(0, 0).lineTo(0, 20)
                .moveTo(80, 0).lineTo(80, 20).moveTo(40, 10).lineTo(40, 20).stroke());
        grid.setPosition(Position.Absolute).setBox(10d, 10d, 80d, 20d);
        return new VirtualPage(100d, 50d).add(grid)
                .add(paragraph(title, 15, 10, 70, 10))
                .add(paragraph(left, 15, 20, 30, 10))
                .add(paragraph(right, 55, 20, 30, 10));
    }

    private VirtualPage singleCellTablePage(String value) {
        Canvas grid = new Canvas(80d, 15d).setDrawer(context -> context.beginPath()
                .rect(0, 0, 80, 15).stroke());
        grid.setPosition(Position.Absolute).setBox(10d, 10d, 80d, 15d);
        return new VirtualPage(100d, 50d).add(grid).add(paragraph(value, 15, 10, 70, 15));
    }

    private Paragraph paragraph(String value, double x, double y, double width, double height) {
        Paragraph paragraph = new Paragraph(value, 3.5d);
        paragraph.setPosition(Position.Absolute).setBox(x, y, width, height);
        return paragraph;
    }

    private OfdToXlsxConverter converter() {
        return new OfdToXlsxConverter(new SafeOfdExtractor(), new OfdrwParser(), new PageLayoutAnalyzer());
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/ofd", Files.size(source), source);
    }
}
