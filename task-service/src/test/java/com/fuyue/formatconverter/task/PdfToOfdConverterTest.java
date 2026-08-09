package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfToOfdConverterTest {
    @TempDir Path temp;

    @Test
    void writesRealMultiPageOfdWithOriginalGeometryTextVectorAndImageObjects() throws Exception {
        Path source = temp.resolve("layout.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font cjk = loadFont(pdf, "/fonts/DroidSansFallback.ttf");
            addPage(pdf, 120, 80, cjk, "第一页转换固定版式", true);
            addPage(pdf, 80, 120, cjk, "第二页可提取文字", false);
            pdf.save(source.toFile());
        }
        Path output = temp.resolve("layout.ofd");

        ConversionOutput converted = new PdfToOfdConverter().convert(
                new ConversionInput("layout.pdf", "application/pdf", Files.size(source), source),
                temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(2, converted.pageCount());
        assertEquals("layout.ofd", converted.outputName());
        assertTrue(Files.size(output) > 0);
        try (ZipFile archive = new ZipFile(output.toFile())) {
            assertNotNull(archive.getEntry("OFD.xml"));
            assertTrue(archive.stream().anyMatch(entry -> entry.getName().endsWith("Content.xml")));
        }

        var safe = new SafeOfdExtractor().extract(output, temp.resolve("unpacked"), ParseLimits.defaults());
        DocumentModel parsed = new OfdrwParser().parse(safe, "layout.ofd", ParseLimits.defaults());
        assertEquals(2, parsed.pages().size());
        assertEquals(120d, parsed.pages().get(0).physicalBox().width(), 0.1d);
        assertEquals(80d, parsed.pages().get(0).physicalBox().height(), 0.1d);
        assertEquals(80d, parsed.pages().get(1).physicalBox().width(), 0.1d);
        assertEquals(120d, parsed.pages().get(1).physicalBox().height(), 0.1d);
        String text = parsed.pages().stream().flatMap(page -> page.textBlocks().stream())
                .map(block -> block.text()).reduce("", String::concat);
        assertTrue(text.contains("第一页转换固定版式"), text);
        assertTrue(text.contains("第二页可提取文字"), text);
        assertTrue(parsed.pages().get(0).images().size() >= 1, "PDF 图片应成为 OFD 图片对象");
        assertTrue(converted.warnings().stream().anyMatch(warning -> warning.code().name().equals("FIDELITY_IMAGE_LAYER")));
    }

    @Test
    void preservesImageOnlyPdfAsOfdPageWithoutRequiringOcr() throws Exception {
        Path source = temp.resolve("scan.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(points(100), points(60)));
            pdf.addPage(page);
            BufferedImage image = new BufferedImage(400, 240, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("scan", 180, 120);
            graphics.dispose();
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, image), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            pdf.save(source.toFile());
        }
        Path output = temp.resolve("scan.ofd");

        ConversionOutput converted = new PdfToOfdConverter().convert(
                new ConversionInput("scan.pdf", "application/pdf", Files.size(source), source),
                temp.resolve("scan-work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(1, converted.pageCount());
        var safe = new SafeOfdExtractor().extract(output, temp.resolve("scan-unpacked"), ParseLimits.defaults());
        DocumentModel parsed = new OfdrwParser().parse(safe, "scan.ofd", ParseLimits.defaults());
        assertTrue(parsed.pages().get(0).textBlocks().isEmpty());
        assertEquals(1, parsed.pages().get(0).images().size());
        assertTrue(converted.warnings().stream().anyMatch(warning -> warning.code().name().equals("FIDELITY_IMAGE_LAYER")));
    }

    private void addPage(PDDocument pdf, double widthMm, double heightMm,
                         PDType0Font font, String text, boolean withImage) throws Exception {
        PDPage page = new PDPage(new PDRectangle(points(widthMm), points(heightMm)));
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(font, 13);
            content.newLineAtOffset(24, page.getMediaBox().getHeight() - 35);
            content.showText(text);
            content.endText();
            content.setStrokingColor(Color.BLUE);
            content.setLineWidth(2);
            content.moveTo(20, 35);
            content.lineTo(page.getMediaBox().getWidth() - 20, 35);
            content.stroke();
            if (withImage) {
                BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.RED);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.dispose();
                content.drawImage(LosslessFactory.createFromImage(pdf, image), 30, 50, 60, 40);
            }
        }
    }

    private PDType0Font loadFont(PDDocument document, String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return PDType0Font.load(document, input);
        }
    }

    private float points(double millimetres) {
        return (float) (millimetres * 72d / 25.4d);
    }
}
