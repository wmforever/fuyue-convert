package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.DocumentModel;
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
import java.time.Duration;

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
            // Read actual text nodes; POI's paragraph accessor adds parentheses around VML boxes.
            var xml = org.apache.poi.util.XMLHelper.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    word.getDocument().xmlText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var nodes = xml.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
            StringBuilder contents = new StringBuilder();
            for (int index = 0; index < nodes.getLength(); index++) contents.append(nodes.item(index).getTextContent());
            String text = contents.toString();
            assertTrue(text.contains("REAL OFD PAGE"), text);
            assertTrue(text.contains("OFD SCANNED 2026"), text);
            assertFalse(word.getAllPictures().isEmpty(), "OFD scan image should remain as a fidelity layer");
        }
        assertEquals(2, wordOutput.pageCount());
        assertEquals(1, wordOutput.warnings().stream()
                .filter(warning -> warning.code() == WarningCode.OCR_APPLIED).count());
    }

    @Test
    void unavailableOcrFailsOnLargeScannedRegionEvenWhenSamePageHasNativeHeader() throws Exception {
        Path source = createSamePageMixedOfd();
        var capability = new TesseractOcrConverter.Capability(true, false, null,
                "OCR_LANGUAGE_MISSING", "缺少 OCR 语言包：chi_sim", "tesseract", "chi_sim",
                java.util.Set.of("eng"), "fake");
        Path output = temp.resolve("same-page-unavailable.docx");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new OfdToDocxConverter(new SafeOfdExtractor(), new OfdrwParser(),
                        new PageLayoutAnalyzer(), new PoiDocxRenderer(), new OfdOcrSupport(capability))
                        .convert(input(source), temp.resolve("same-page-unavailable-work"), output,
                                ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_LANGUAGE_MISSING", failure.code());
        assertFalse(Files.exists(output));
    }

    @Test
    void requiredSamePageImageOcrFailureDoesNotPublishPartialDocx() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path source = createSamePageMixedOfd();
        Path binary = fakeTesseractWithoutText();
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        Path output = temp.resolve("same-page-failed.docx");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new OfdToDocxConverter(new SafeOfdExtractor(), new OfdrwParser(),
                        new PageLayoutAnalyzer(), new PoiDocxRenderer(), new OfdOcrSupport(settings))
                        .convert(input(source), temp.resolve("same-page-failed-work"), output,
                                ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_NO_TEXT", failure.code());
        assertFalse(Files.exists(output));
    }

    @Test
    void overlappingOcrRegionsAreDeduplicatedAgainstEarlierOcrResults() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path source = createOverlappingScanRegionsOfd();
        Path binary = fakeTesseractWithText("ONEWORD", "99.0");
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        SafeOfdExtractor extractor = new SafeOfdExtractor();
        DocumentModel parsed = new OfdrwParser().parse(
                extractor.extract(source, temp.resolve("overlapping-extract"), ParseLimits.defaults()),
                source.getFileName().toString(), ParseLimits.defaults());

        assertEquals(2, parsed.pages().get(0).images().size());
        DocumentModel recognized = new OfdOcrSupport(settings).recognizeRequiredPages(
                parsed, temp.resolve("overlapping-ofd-ocr-work"), ParseLimits.defaults(),
                (stage, percent) -> { });

        var page = recognized.pages().get(0);
        assertEquals(1, page.textBlocks().stream().filter(block -> block.text().equals("ONEWORD")).count());
        assertEquals(1, page.textBlocks().stream().filter(block -> block.text().equals("ONEWORD"))
                .findFirst().orElseThrow().ocrWords().size(),
                "OFD 追加图像标识时须保留词框，供 Word 精确定位和局部遮盖");
        assertEquals(2, page.images().size());
        assertTrue(page.images().stream()
                .allMatch(image -> image.role().equals("OCR_SCAN_BACKGROUND")));
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

    private Path createSamePageMixedOfd() throws Exception {
        BufferedImage raster = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 82));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("OFD SCANNED BODY", 80, 300);
        graphics.drawString("CONTENT MUST SURVIVE", 80, 610);
        graphics.dispose();
        Path png = temp.resolve("same-page-scan.png");
        ImageIO.write(raster, "png", png.toFile());

        Img image = new Img(190d, 90d, png);
        image.setPosition(Position.Absolute).setBox(10d, 22d, 190d, 90d);
        Paragraph header = new Paragraph("表头", 5d);
        header.setPosition(Position.Absolute).setBox(10d, 5d, 40d, 10d);
        Path source = temp.resolve("same-page-mixed.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(210d, 120d).add(image).add(header));
        }
        return source;
    }

    private Path createOverlappingScanRegionsOfd() throws Exception {
        BufferedImage raster = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 82));
        graphics.drawString("OVERLAPPING SCAN", 80, 300);
        graphics.drawString("CONTENT MUST SURVIVE", 80, 610);
        graphics.dispose();
        Path png = temp.resolve("overlapping-ofd-scan.png");
        ImageIO.write(raster, "png", png.toFile());

        Img first = new Img(190d, 100d, png);
        first.setPosition(Position.Absolute).setBox(10d, 10d, 190d, 100d);
        Img second = new Img(190d, 100d, png);
        second.setPosition(Position.Absolute).setBox(10d, 10d, 190d, 100d);
        Path source = temp.resolve("overlapping-scan-regions.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(210d, 120d).add(first).add(second));
        }
        return source;
    }

    private Path fakeTesseractWithoutText() throws Exception {
        Path binary = temp.resolve("fake-ofd-tesseract-no-text");
        String script = "#!/bin/sh\n"
                + "base=\"$2\"\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n";
        Files.writeString(binary, script);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    private Path fakeTesseractWithText(String text, String confidence) throws Exception {
        Path binary = temp.resolve("fake-ofd-tesseract-with-text-" + System.nanoTime());
        String script = "#!/bin/sh\n"
                + "base=\"$2\"\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t20\\t10\\t150\\t60\\t" + confidence
                + "\\t" + text + "\\n' >> \"${base}.tsv\"\n";
        Files.writeString(binary, script);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/ofd", Files.size(source), source);
    }
}
