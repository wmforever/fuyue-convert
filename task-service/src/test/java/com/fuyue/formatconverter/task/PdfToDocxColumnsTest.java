package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PdfToDocxColumnsTest {
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    @TempDir Path temp;

    @Test void keepsWideGutterColumnsEditableAtTheirSourcePositionsInColumnReadingOrder() throws Exception {
        Path source = temp.resolve("columns.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                addText(content, 50, 800, "COLUMN REPORT");
                // Deliberately write row by row to distinguish reading order
                // from the original PDF drawing sequence.
                addText(content, 50, 760, "LEFT-1");
                addText(content, 350, 760, "RIGHT-1");
                addText(content, 50, 720, "LEFT-2");
                addText(content, 350, 720, "RIGHT-2");
            }
            pdf.save(source.toFile());
        }
        Path output = convert(source);

        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            assertTrue(word.getAllPictures().isEmpty(), "纯文字 PDF 不应退化为图片");
            assertEquals(List.of("COLUMN REPORT"), plainBodyTexts(word),
                    "跨栏标题应继续保留为普通正文段落");
        }
        try (ZipFile archive = new ZipFile(output.toFile())) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(archive.getInputStream(archive.getEntry("word/document.xml")));
            var shapes = document.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "shape");
            List<String> shapeTexts = new ArrayList<>();
            List<String> shapeStyles = new ArrayList<>();
            for (int index = 0; index < shapes.getLength(); index++) {
                Element shape = (Element) shapes.item(index);
                var texts = shape.getElementsByTagNameNS(WORD_NS, "t");
                if (texts.getLength() == 0) continue;
                StringBuilder value = new StringBuilder();
                for (int run = 0; run < texts.getLength(); run++) value.append(texts.item(run).getTextContent());
                shapeTexts.add(value.toString());
                shapeStyles.add(shape.getAttribute("style"));
            }
            assertEquals(List.of("LEFT-1", "LEFT-2", "RIGHT-1", "RIGHT-2"), shapeTexts,
                    "每栏应从上向下阅读，不能用空格把左右栏连成一段");
            for (int index = 0; index < shapeStyles.size(); index++) {
                assertTrue(shapeStyles.get(index).contains("margin-left:" + (index < 2 ? "50.000" : "350.000") + "pt;"),
                        shapeStyles.get(index));
                assertTrue(shapeStyles.get(index).contains("mso-position-horizontal-relative:page"));
            }
            var allTexts = document.getElementsByTagNameNS(WORD_NS, "t");
            assertEquals(5, allTexts.getLength(), "标题和四段正文不能丢失或重复");
        }
    }

    @Test void keepsOrdinarySingleColumnBodyAsWordParagraphs() throws Exception {
        Path source = temp.resolve("body.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                addText(content, 50, 760, "Plain body one");
                addText(content, 50, 730, "Plain body two");
            }
            pdf.save(source.toFile());
        }
        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(convert(source)))) {
            assertEquals(List.of("Plain body one", "Plain body two"), word.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText()).filter(text -> !text.isBlank()).toList());
            assertFalse(word.getDocument().xmlText().contains("txbxContent"));
            assertTrue(word.getAllPictures().isEmpty());
        }
    }

    @Test void keepsUnequalColumnTailsInReadingOrderAndStopsAtASpanningHeading() throws Exception {
        for (boolean longerLeft : List.of(true, false)) {
            String tail = longerLeft ? "LEFT-3" : "RIGHT-3";
            String sectionHeading = "A SPANNING SECTION HEADING BREAKS BOTH COLUMNS";
            Path source = temp.resolve(longerLeft ? "left-tail.pdf" : "right-tail.pdf");
            try (PDDocument pdf = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    addText(content, 50, 800, "COLUMN REPORT");
                    addText(content, 50, 760, "LEFT-1");
                    addText(content, 350, 760, "RIGHT-1");
                    addText(content, 50, 720, "LEFT-2");
                    addText(content, 350, 720, "RIGHT-2");
                    addText(content, longerLeft ? 50 : 350, 680, tail);
                    addText(content, 50, 640, sectionHeading);
                    addText(content, 50, 610, "Ordinary body after the columns");
                }
                pdf.save(source.toFile());
            }
            Path output = convert(source);
            try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
                assertTrue(word.getAllPictures().isEmpty());
                assertEquals(List.of("COLUMN REPORT", sectionHeading, "Ordinary body after the columns"),
                        plainBodyTexts(word),
                        "跨栏标题与后续普通正文不能被吸收到前面的双栏区域");
            }
            try (ZipFile archive = new ZipFile(output.toFile())) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var document = factory.newDocumentBuilder().parse(
                        archive.getInputStream(archive.getEntry("word/document.xml")));
                var shapes = document.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "shape");
                List<String> shapeTexts = new ArrayList<>();
                for (int index = 0; index < shapes.getLength(); index++) {
                    Element shape = (Element) shapes.item(index);
                    var texts = shape.getElementsByTagNameNS(WORD_NS, "t");
                    if (texts.getLength() > 0) shapeTexts.add(texts.item(0).getTextContent());
                }
                assertEquals(longerLeft
                                ? List.of("LEFT-1", "LEFT-2", "LEFT-3", "RIGHT-1", "RIGHT-2")
                                : List.of("LEFT-1", "LEFT-2", "RIGHT-1", "RIGHT-2", "RIGHT-3"),
                        shapeTexts, "较长一栏的尾行也必须参与该栏的阅读顺序");
                assertEquals(8, document.getElementsByTagNameNS(WORD_NS, "t").getLength(),
                        "每条文字应完整保留一次");
            }
        }
    }

    private Path convert(Path source) throws Exception {
        Path output = temp.resolve(source.getFileName().toString().replace(".pdf", ".docx"));
        new PdfToDocxConverter().convert(new ConversionInput(source.getFileName().toString(), "application/pdf",
                        Files.size(source), source), temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });
        return output;
    }

    private List<String> plainBodyTexts(XWPFDocument word) {
        // A normal body heading may anchor floating column shapes. Its direct
        // text runs remain ordinary editable body text, outside the textboxes.
        return word.getParagraphs().stream().map(p -> p.getCTP().getRList().stream()
                .flatMap(r -> r.getTList().stream()).map(t -> t.getStringValue())
                .collect(java.util.stream.Collectors.joining())).filter(t -> !t.isBlank()).toList();
    }

    private void addText(PDPageContentStream content, float x, float y, String text) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
