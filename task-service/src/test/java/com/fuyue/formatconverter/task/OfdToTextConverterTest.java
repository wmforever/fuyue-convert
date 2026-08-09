package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.LineElement;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ParagraphModel;
import com.fuyue.formatconverter.model.Point;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfdToTextConverterTest {
    @TempDir Path temp;

    @Test
    void includesTableAndFloatingTextWhenParagraphsOnlyContainBodyText() {
        TextBlock title = text("title", 10, 10, "发票标题");
        TextBlock tableLabel = text("table-label", 10, 20, "购买方");
        TextBlock tableValue = text("table-value", 40, 20, "测试公司");
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297),
                List.of(title, tableValue, tableLabel), List.of(), List.of(),
                List.of(new ParagraphModel(title.box(), List.of(title), ParagraphModel.Alignment.LEFT, 0)),
                List.of(), List.of());

        assertEquals("发票标题\n购买方测试公司\n", normalizeLines(OfdToTextConverter.text(List.of(page))));
    }

    @Test
    void readsEachColumnTopToBottomBeforeMovingRight() {
        PageModel page = new PageModel(1, new Rect(0, 0, 210, 297),
                List.of(
                        text("right-2", 125, 35, "右栏第二行"),
                        text("left-1", 15, 20, "左栏第一行"),
                        text("right-1", 125, 20, "右栏第一行"),
                        text("left-2", 15, 35, "左栏第二行")),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals("左栏第一行\n左栏第二行\n右栏第一行\n右栏第二行\n",
                normalizeLines(OfdToTextConverter.text(List.of(page))));
    }

    @Test
    void emitsRecognizedTableAsRowsAndTabSeparatedColumnsWithoutDuplicates() {
        List<LineElement> grid = List.of(
                line("h1", 10, 10, 90, 10), line("h2", 10, 20, 90, 20),
                line("h3", 10, 30, 90, 30), line("v1", 10, 10, 10, 30),
                line("v2", 50, 10, 50, 30), line("v3", 90, 10, 90, 30));
        PageModel raw = new PageModel(1, new Rect(0, 0, 100, 60),
                List.of(text("a", 15, 17, "甲"), text("b", 55, 17, "乙"),
                        text("c", 15, 27, "丙"), text("d", 55, 27, "丁")),
                grid, List.of(), List.of(), List.of(), List.of());
        PageModel analyzed = new PageLayoutAnalyzer().analyze(raw);

        assertEquals("甲\t乙\n丙\t丁\n", normalizeLines(OfdToTextConverter.text(List.of(analyzed))));
    }

    @Test
    void scannedOfdFailsWithOcrRequiredAndDoesNotCreateTxt() throws Exception {
        Path source = scannedOfd("scan.ofd", false);
        Path output = temp.resolve("scan.txt");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("scan-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_REQUIRED", failure.code());
        assertTrue(failure.getMessage().contains("第 1 页"));
        assertFalse(Files.exists(output));
    }

    @Test
    void mixedTextAndScannedOfdFailsInsteadOfDroppingScannedPage() throws Exception {
        Path source = scannedOfd("mixed.ofd", true);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("mixed-work"), temp.resolve("mixed.txt"),
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_REQUIRED", failure.code());
        assertTrue(failure.getMessage().contains("第 2 页"));
    }

    private TextBlock text(String id, double x, double baseline, String value) {
        return new TextBlock(id, 1, new Rect(x, baseline - 4, 20, 5), value, baseline,
                new FontStyle("SimSun", 10, false, false, ColorValue.BLACK), 0);
    }

    private LineElement line(String id, double x1, double y1, double x2, double y2) {
        return new LineElement(id, 1, new Point(x1, y1), new Point(x2, y2), 0.2, ColorValue.BLACK, 0);
    }

    private OfdToTextConverter converter() {
        return new OfdToTextConverter(new SafeOfdExtractor(), new OfdrwParser(), new PageLayoutAnalyzer());
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/ofd",
                Files.size(source), source);
    }

    private Path scannedOfd(String name, boolean includeTextPage) throws Exception {
        Path png = temp.resolve(name + ".png");
        BufferedImage raster = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.drawString("scanned content", 200, 200);
        graphics.dispose();
        ImageIO.write(raster, "png", png.toFile());

        Path source = temp.resolve(name);
        Img image = new Img(120d, 80d, png);
        image.setPosition(Position.Absolute).setBox(0d, 0d, 120d, 80d);
        try (OFDDoc document = new OFDDoc(source)) {
            if (includeTextPage) {
                Paragraph paragraph = new Paragraph("第一页真实文字", 5d);
                paragraph.setPosition(Position.Absolute).setBox(10d, 10d, 100d, 15d);
                document.addVPage(new VirtualPage(120d, 80d).add(paragraph));
            }
            document.addVPage(new VirtualPage(120d, 80d).add(image));
        }
        return source;
    }

    private String normalizeLines(String value) {
        return value.replace("\r\n", "\n");
    }
}
