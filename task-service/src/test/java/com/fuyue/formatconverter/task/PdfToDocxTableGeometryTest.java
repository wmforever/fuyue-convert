package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TableModel;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.*;

class PdfToDocxTableGeometryTest {
    @TempDir Path temp;
    private static final Set<String> LABELS = Set.of("A1", "B1", "A2", "B2");

    @Test
    void closedRectangleRetainsAllFourOuterBorders() throws Exception {
        Path source = grid("closed", false, false, 0, false);
        PageModel page = parsed(source);
        assertEquals(6, page.lines().size(), "four outer borders and two internal rules");
        assertEditableTable(source);
    }

    @Test
    void translatedAndScaledGridSharesCoordinatesWithItsText() throws Exception {
        Path source = grid("transformed", false, true, 0, false);
        PageModel page = parsed(source);
        TableModel table = assertTable(page);
        assertEquals(150 * 25.4 / 72, table.box().x(), 0.01);
        assertEquals(288 * 25.4 / 72, table.box().width(), 0.01);
        assertEditableTable(source);
    }

    @Test
    void narrowFilledRectanglesBecomeTableRulesButWideBackgroundDoesNot() throws Exception {
        Path source = grid("filled", true, false, 0, false);
        PageModel page = parsed(source);
        assertEquals(6, page.lines().size(), "the wide filled background is not a table border");
        assertEditableTable(source);
    }

    @Test
    void croppedAndRotatedPagesKeepGridAndCellTextTogether() throws Exception {
        for (int rotation : List.of(0, 90, 180, 270)) {
            Path source = grid("crop-" + rotation, false, true, rotation, true);
            PageModel page = parsed(source);
            TableModel table = assertTable(page);
            assertTrue(table.box().x() >= 0 && table.box().y() >= 0);
            assertTrue(table.box().right() <= page.physicalBox().right());
            assertTrue(table.box().bottom() <= page.physicalBox().bottom());
            if (rotation == 0) assertEditableTable(source);
            else assertPositionedTable(source, rotation);
        }
    }

    @Test
    void quarterTurnCellTextStaysRotatedWhenWordIsOpenedByLibreOffice() throws Exception {
        var binary = LibreOfficeConverter.discover("");
        org.junit.jupiter.api.Assumptions.assumeTrue(binary.isPresent(), "LibreOffice is not installed");
        for (int rotation : List.of(90, 270)) {
            Path source = grid("office-" + rotation, false, false, rotation, false);
            assertPositionedTable(source, rotation);
            Path docx = temp.resolve(source.getFileName() + ".docx");
            Path rendered = temp.resolve("rendered-" + rotation + ".pdf");
            new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF, binary.orElseThrow(),
                    Duration.ofSeconds(45), "rotation regression").convert(
                    new ConversionInput(docx.getFileName().toString(), DocumentFormat.DOCX.contentType(),
                            Files.size(docx), docx), temp.resolve("office-work-" + rotation), rendered,
                    ParseLimits.defaults(), (stage, progress) -> { });
            var pages = new PdfLayoutParser().parse(rendered, "rendered.pdf", ParseLimits.defaults()).pages();
            assertEquals(1, pages.size());
            String text = pages.get(0).textBlocks().stream().map(block -> block.text())
                    .reduce("", String::concat).replaceAll("\\s+", "");
            assertEquals(8, text.length());
            LABELS.forEach(label -> assertTrue(text.contains(label), text));
            for (var block : pages.get(0).textBlocks()) {
                assertEquals(rotation == 90 ? 90d : -90d, block.transform().rotationDegrees(), 0.1d,
                        "the office reader must rotate actual glyphs, not just retain an XML rotation attribute");
            }
        }
    }

    private void assertPositionedTable(Path source, int rotation) throws Exception {
        Path output = temp.resolve(source.getFileName() + ".docx");
        var result = new PdfToDocxConverter().convert(new ConversionInput(source.getFileName().toString(),
                        "application/pdf", Files.size(source), source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, progress) -> { });
        assertEquals(rotation == 180, result.warnings().stream()
                .anyMatch(warning -> warning.code() == WarningCode.UNSUPPORTED_TEXT_TRANSFORM));
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(output))) {
            assertTrue(document.getTables().isEmpty(), "rotated text must not become horizontal table runs");
            assertTrue(document.getAllPictures().isEmpty());
        }
        try (ZipFile archive = new ZipFile(output.toFile())) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(archive.getInputStream(archive.getEntry("word/document.xml")));
            String word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
            var shapes = document.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "shape");
            Set<String> values = new HashSet<>();
            for (int index = 0; index < shapes.getLength(); index++) {
                Element shape = (Element) shapes.item(index);
                var text = shape.getElementsByTagNameNS(word, "t");
                if (text.getLength() == 0) continue;
                values.add(text.item(0).getTextContent());
                if (rotation == 180) {
                    assertTrue(shape.getAttribute("style").contains("rotation:180.000"));
                } else {
                    assertFalse(shape.getAttribute("style").contains("rotation:"));
                    Element textbox = (Element) shape.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "textbox").item(0);
                    assertTrue(textbox.getAttribute("style").contains("layout-flow:vertical"));
                    assertTrue(textbox.getAttribute("style").contains("mso-layout-flow-alt:"
                            + (rotation == 90 ? "top-to-bottom" : "bottom-to-top")));
                }
            }
            assertEquals(LABELS, values);
            assertEquals(4, document.getElementsByTagNameNS(word, "t").getLength(), "each label appears once");
            assertEquals(6, document.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "rect").getLength(),
                    "source grid remains visible around editable rotated text");
        }
    }

    private PageModel parsed(Path source) throws Exception {
        return new PdfLayoutParser().parse(source, source.getFileName().toString(), ParseLimits.defaults())
                .pages().get(0);
    }

    private TableModel assertTable(PageModel page) {
        PageModel analyzed = new PageLayoutAnalyzer().analyze(page);
        assertEquals(1, analyzed.tables().size());
        TableModel table = analyzed.tables().get(0);
        assertEquals(2, table.rowCount());
        assertEquals(2, table.columnCount());
        Set<String> text = new HashSet<>();
        table.cells().forEach(cell -> cell.paragraphs().forEach(paragraph ->
                paragraph.runs().forEach(run -> text.add(run.text()))));
        assertEquals(LABELS, text);
        assertTrue(analyzed.paragraphs().isEmpty(), "all four labels belong to cells");
        return table;
    }

    private void assertEditableTable(Path source) throws Exception {
        assertTable(parsed(source));
        Path output = temp.resolve(source.getFileName() + ".docx");
        new PdfToDocxConverter().convert(new ConversionInput(source.getFileName().toString(),
                        "application/pdf", Files.size(source), source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, progress) -> { });
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(output))) {
            assertEquals(1, document.getTables().size());
            var table = document.getTables().get(0);
            assertEquals(2, table.getRows().size());
            Set<String> values = new HashSet<>();
            table.getRows().forEach(row -> {
                assertEquals(2, row.getTableCells().size());
                row.getTableCells().forEach(cell -> values.add(cell.getText()));
            });
            assertEquals(LABELS, values, "labels stay editable in their own Word table cells");
        }
    }

    private Path grid(String name, boolean filled, boolean transformed, int rotation, boolean cropped)
            throws Exception {
        Path source = temp.resolve(name + ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(700, 850));
            if (cropped) page.setCropBox(new PDRectangle(50, 80, 500, 600));
            page.setRotation(rotation);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                if (transformed) content.transform(new Matrix(1.2f, 0, 0, .9f, 30, 40));
                if (filled) {
                    content.setNonStrokingColor(0.9f);
                    content.addRect(100, 400, 240, 80);
                    content.fill();
                    content.setNonStrokingColor(0f);
                    for (int x : List.of(100, 220, 340)) content.addRect(x - .3f, 400, .6f, 80);
                    for (int y : List.of(400, 440, 480)) content.addRect(100, y - .3f, 240, .6f);
                    content.fill();
                } else {
                    content.addRect(100, 400, 240, 80);
                    content.moveTo(220, 400); content.lineTo(220, 480);
                    content.moveTo(100, 440); content.lineTo(340, 440);
                    content.stroke();
                }
                var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                int index = 0;
                for (String label : List.of("A1", "B1", "A2", "B2")) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(125 + (index % 2) * 120, 455 - (index / 2) * 40);
                    content.showText(label);
                    content.endText();
                    index++;
                }
            }
            document.save(source.toFile());
        }
        return source;
    }
}
