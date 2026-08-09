package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfdOcrConverterTest {
    @TempDir Path temp;

    @Test
    void strictModeRejectsScanAndConfiguredOcrFillsTxtAndEditableDocx() throws Exception {
        var discovered = TesseractOcrConverter.discover("");
        assumeTrue(discovered.isPresent(), "Tesseract is not installed");
        assumeTrue(TesseractOcrConverter.languages(discovered.orElseThrow()).contains("eng"),
                "Tesseract English model is not installed");
        var settings = new TesseractOcrConverter.Settings(discovered.orElseThrow(), "eng",
                TesseractOcrConverter.version(discovered.orElseThrow()).orElse("unknown"));
        Path source = createMixedOfd();
        SafeOfdExtractor extractor = new SafeOfdExtractor();
        OfdrwParser parser = new OfdrwParser();
        PageLayoutAnalyzer analyzer = new PageLayoutAnalyzer();

        Path strictOutput = temp.resolve("strict.docx");
        ConversionFailureException strict = assertThrows(ConversionFailureException.class,
                () -> new OfdToDocxConverter(extractor, parser, analyzer, new PoiDocxRenderer())
                        .convert(input(source), temp.resolve("strict-work"), strictOutput,
                                ParseLimits.defaults(), (stage, percent) -> { }));
        assertEquals("OCR_REQUIRED", strict.code());
        assertFalse(Files.exists(strictOutput));

        Path txt = temp.resolve("mixed.txt");
        ConversionOutput textOutput = new OfdToTextConverter(extractor, parser, analyzer,
                new OfdOcrSupport(settings)).convert(input(source), temp.resolve("txt-work"), txt,
                ParseLimits.defaults(), (stage, percent) -> { });
        String extracted = Files.readString(txt);
        assertTrue(extracted.contains("REAL OFD PAGE"), extracted);
        assertTrue(extracted.contains("OFD SCANNED 2026"), extracted);
        assertEquals(2, textOutput.pageCount());
        assertEquals(1, textOutput.warnings().stream()
                .filter(warning -> warning.code() == WarningCode.OCR_APPLIED).count());

        Path docx = temp.resolve("mixed.docx");
        ConversionOutput wordOutput = new OfdToDocxConverter(extractor, parser, analyzer,
                new PoiDocxRenderer(), new OfdOcrSupport(settings)).convert(input(source),
                temp.resolve("docx-work"), docx, ParseLimits.defaults(), (stage, percent) -> { });
        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(docx))) {
            String text = word.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(text.contains("REAL OFD PAGE"), text);
            assertTrue(text.contains("OFD SCANNED 2026"), text);
            assertFalse(word.getAllPictures().isEmpty(), "OFD scan image should remain as a fidelity layer");
        }
        assertEquals(2, wordOutput.pageCount());
        assertEquals(1, wordOutput.warnings().stream()
                .filter(warning -> warning.code() == WarningCode.OCR_APPLIED).count());
    }

    private Path createMixedOfd() throws Exception {
        BufferedImage raster = new BufferedImage(1400, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 92));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("OFD SCANNED 2026", 100, 300);
        graphics.dispose();
        Path png = temp.resolve("scan.png");
        ImageIO.write(raster, "png", png.toFile());

        Path source = temp.resolve("mixed.ofd");
        Paragraph paragraph = new Paragraph("REAL OFD PAGE", 8d);
        paragraph.setPosition(Position.Absolute).setBox(10d, 10d, 180d, 20d);
        Img image = new Img(190d, 80d, png);
        image.setPosition(Position.Absolute).setBox(10d, 20d, 190d, 80d);
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(210d, 100d).add(paragraph));
            document.addVPage(new VirtualPage(210d, 120d).add(image));
        }
        return source;
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/ofd", Files.size(source), source);
    }
}
