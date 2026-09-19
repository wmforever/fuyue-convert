package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class PdfOcrConverterTest {
    @TempDir Path temp;

    @Test
    void fillsOnlyScannedPageForTxtAndKeepsSourceImageBehindEditableDocxText() throws Exception {
        var discovered = TesseractOcrConverter.discover("");
        assumeTrue(discovered.isPresent(), "Tesseract is not installed");
        assumeTrue(TesseractOcrConverter.languages(discovered.orElseThrow()).contains("eng"),
                "Tesseract English model is not installed");
        var settings = new TesseractOcrConverter.Settings(discovered.orElseThrow(), "eng",
                TesseractOcrConverter.version(discovered.orElseThrow()).orElse("unknown"));
        Path source = createMixedPdf();
        PageLayoutAnalyzer analyzer = new PageLayoutAnalyzer();

        Path txt = temp.resolve("mixed.txt");
        ConversionOutput textOutput = new PdfToTextConverter(new PdfLayoutParser(), analyzer,
                new PdfOcrSupport(settings)).convert(input(source), temp.resolve("txt-work"), txt,
                ParseLimits.defaults(), (stage, percent) -> { });

        String extracted = Files.readString(txt);
        assertTrue(extracted.contains("REAL TEXT PAGE"), extracted);
        assertTrue(extracted.contains("SCANNED OCR 2026"), extracted);
        assertEquals(2, textOutput.pageCount());
        assertEquals(1, textOutput.warnings().stream()
                .filter(warning -> warning.code() == WarningCode.OCR_APPLIED).count());

        Path docx = temp.resolve("mixed.docx");
        ConversionOutput wordOutput = new PdfToDocxConverter(new PdfLayoutParser(), analyzer,
                new PoiDocxRenderer(), new PdfOcrSupport(settings)).convert(input(source),
                temp.resolve("docx-work"), docx, ParseLimits.defaults(), (stage, percent) -> { });

        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(docx))) {
            String wordText = word.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(wordText.contains("REAL TEXT PAGE"), wordText);
            assertTrue(wordText.contains("SCANNED OCR 2026"), wordText);
            assertFalse(word.getAllPictures().isEmpty(),
                    "OCR DOCX keeps the scan source until complete OCR coverage can be proven");
        }
        assertEquals(2, wordOutput.pageCount());
        assertEquals(1, wordOutput.warnings().stream()
                .filter(warning -> warning.code() == WarningCode.OCR_APPLIED).count());
    }

    @Test
    void unavailableLanguageDoesNotBlockNativeTextButFailsOnScannedPageWithStableCode() throws Exception {
        var capability = new TesseractOcrConverter.Capability(true, false, null,
                "OCR_LANGUAGE_MISSING", "缺少 OCR 语言包：chi_sim", "tesseract", "chi_sim",
                java.util.Set.of("eng"), "fake");
        PdfOcrSupport unavailable = new PdfOcrSupport(capability);
        PageLayoutAnalyzer analyzer = new PageLayoutAnalyzer();
        Path textOnly = createTextOnlyPdf();
        Path textOutput = temp.resolve("native.txt");

        ConversionOutput nativeResult = new PdfToTextConverter(new PdfLayoutParser(), analyzer, unavailable)
                .convert(input(textOnly), temp.resolve("native-work"), textOutput,
                        ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(1, nativeResult.pageCount());
        assertTrue(Files.readString(textOutput).contains("NATIVE TEXT"));

        Path mixed = createMixedPdf();
        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new PdfToTextConverter(new PdfLayoutParser(), analyzer, unavailable)
                        .convert(input(mixed), temp.resolve("missing-lang-work"), temp.resolve("missing.txt"),
                                ParseLimits.defaults(), (stage, percent) -> { }));
        assertEquals("OCR_LANGUAGE_MISSING", failure.code());
    }

    @Test
    void unavailableOcrFailsOnLargeScannedRegionEvenWhenSamePageHasNativeHeader() throws Exception {
        var capability = new TesseractOcrConverter.Capability(true, false, null,
                "OCR_LANGUAGE_MISSING", "缺少 OCR 语言包：chi_sim", "tesseract", "chi_sim",
                java.util.Set.of("eng"), "fake");
        Path source = createSamePageMixedPdf();
        Path output = temp.resolve("same-page-unavailable.docx");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new PdfToDocxConverter(new PdfLayoutParser(), new PageLayoutAnalyzer(),
                        new PoiDocxRenderer(), new PdfOcrSupport(capability)).convert(input(source),
                        temp.resolve("same-page-unavailable-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_LANGUAGE_MISSING", failure.code());
        assertFalse(Files.exists(output));
    }

    @Test
    void requiredSamePageImageOcrFailureDoesNotPublishPartialDocx() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path binary = fakeTesseractWithoutText();
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        Path source = createSamePageMixedPdf();
        Path output = temp.resolve("same-page-failed.docx");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new PdfToDocxConverter(new PdfLayoutParser(), new PageLayoutAnalyzer(),
                        new PoiDocxRenderer(), new PdfOcrSupport(settings)).convert(input(source),
                        temp.resolve("same-page-failed-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_NO_TEXT", failure.code());
        assertFalse(Files.exists(output));
    }

    @Test
    void keepsTextLayerScanBackgroundAndOrdinaryPhotoWithoutInvokingOcr() throws Exception {
        Path source = createTextLayerAndPhotoPdf();
        var settings = new TesseractOcrConverter.Settings(temp.resolve("must-not-run"), "eng", "fake",
                Duration.ofSeconds(1), 1, 0.20d, 0.80d);
        Path output = temp.resolve("background-and-photo.docx");

        new PdfToDocxConverter(new PdfLayoutParser(), new PageLayoutAnalyzer(),
                new PoiDocxRenderer(), new PdfOcrSupport(settings)).convert(input(source),
                temp.resolve("background-photo-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            assertEquals(2, word.getAllPictures().size(),
                    "both scan background and ordinary photo remain to prevent visual content loss");
        }
    }

    @Test
    void fewHighConfidenceOcrWordsDoNotDeleteWholeScanImage() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path binary = fakeTesseractWithText("ONEWORD", "99.0");
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        Path source = createSamePageMixedPdf();
        Path output = temp.resolve("partial-high-confidence.docx");

        new PdfToDocxConverter(new PdfLayoutParser(), new PageLayoutAnalyzer(),
                new PoiDocxRenderer(), new PdfOcrSupport(settings)).convert(input(source),
                temp.resolve("partial-high-confidence-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            String text = word.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", String::concat);
            assertTrue(text.contains("ONEWORD"), text);
            assertFalse(word.getAllPictures().isEmpty(),
                    "a few recognized words are not proof that the full scan was recovered");
        }
    }

    @Test
    void fullPageOcrReplacesVectorOnlyVisualsWithRenderedBackground() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path binary = fakeTesseractWithText("VECTOROCR", "99.0");
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        Path source = createVectorOnlyPdf();
        DocumentModel parsed = new PdfLayoutParser().parseForEditableOcr(
                source, source.getFileName().toString(), ParseLimits.defaults());

        assertTrue(parsed.pages().get(0).images().isEmpty());
        DocumentModel recognized = new PdfOcrSupport(settings).recognizeMissingPages(
                source, parsed, temp.resolve("vector-ocr-work"), ParseLimits.defaults(),
                (stage, percent) -> { });

        var page = recognized.pages().get(0);
        assertTrue(page.textBlocks().stream().anyMatch(block -> block.text().contains("VECTOROCR")));
        assertTrue(page.lines().isEmpty(), "the page render replaces source vector visuals to avoid duplication");
        assertEquals(1, page.images().size());
        assertEquals("OCR_PAGE_BACKGROUND", page.images().get(0).role());
        assertEquals(page.physicalBox(), page.images().get(0).box());
        assertTrue(page.images().get(0).data().length > 0);
    }

    @Test
    void overlappingOcrRegionsKeepOneCumulativeResultAndBothScanBackgrounds() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path binary = fakeTesseractWithText("ONEWORD", "99.0");
        var settings = new TesseractOcrConverter.Settings(binary, "eng", "fake", Duration.ofSeconds(5),
                1, 0.20d, 0.80d);
        Path source = createOverlappingScanRegionsPdf();
        DocumentModel parsed = new PdfLayoutParser().parseForEditableOcr(
                source, source.getFileName().toString(), ParseLimits.defaults());

        assertEquals(2, parsed.pages().get(0).images().size());
        DocumentModel recognized = new PdfOcrSupport(settings).recognizeMissingPages(
                source, parsed, temp.resolve("overlapping-pdf-ocr-work"), ParseLimits.defaults(),
                (stage, percent) -> { });

        var page = recognized.pages().get(0);
        assertEquals(1, page.textBlocks().stream().filter(block -> block.text().equals("ONEWORD")).count());
        assertEquals(2, page.images().size());
        assertTrue(page.images().stream()
                .allMatch(image -> image.role().equals("OCR_SCAN_BACKGROUND")));
    }

    private Path createMixedPdf() throws Exception {
        Path source = temp.resolve("mixed-ocr.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage textPage = new PDPage(PDRectangle.A4);
            pdf.addPage(textPage);
            try (PDPageContentStream content = new PDPageContentStream(pdf, textPage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                content.newLineAtOffset(70, 720);
                content.showText("REAL TEXT PAGE");
                content.endText();
            }

            BufferedImage scan = new BufferedImage(1200, 400, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scan.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, scan.getWidth(), scan.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 82));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString("SCANNED OCR 2026", 80, 240);
            graphics.dispose();
            PDPage scannedPage = new PDPage(PDRectangle.A4);
            pdf.addPage(scannedPage);
            var image = LosslessFactory.createFromImage(pdf, scan);
            try (PDPageContentStream content = new PDPageContentStream(pdf, scannedPage)) {
                content.drawImage(image, 40, 300, 515, 172);
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private Path createSamePageMixedPdf() throws Exception {
        Path source = temp.resolve("same-page-mixed-ocr.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            BufferedImage scan = documentRaster("SCANNED BODY 2026");
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, scan), 35, 40, 525, 680);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                content.newLineAtOffset(45, 790);
                content.showText("HDR");
                content.endText();
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private Path createOverlappingScanRegionsPdf() throws Exception {
        Path source = temp.resolve("overlapping-scan-regions.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            var image = LosslessFactory.createFromImage(pdf, documentRaster("OVERLAPPING SCAN"));
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(image, 35, 40, 525, 680);
                content.drawImage(image, 35, 40, 525, 680);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                content.newLineAtOffset(45, 790);
                content.showText("HDR");
                content.endText();
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private Path createTextLayerAndPhotoPdf() throws Exception {
        Path source = temp.resolve("text-layer-and-photo.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage scanPage = new PDPage(PDRectangle.A4);
            pdf.addPage(scanPage);
            try (PDPageContentStream content = new PDPageContentStream(pdf, scanPage)) {
                content.drawImage(LosslessFactory.createFromImage(pdf,
                        documentRaster("SEARCHABLE BACKGROUND")), 0, 0,
                        scanPage.getMediaBox().getWidth(), scanPage.getMediaBox().getHeight());
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(70, 700);
                for (String line : java.util.List.of(
                        "COMPLETE SEARCHABLE TEXT LINE 01",
                        "COMPLETE SEARCHABLE TEXT LINE 02",
                        "COMPLETE SEARCHABLE TEXT LINE 03",
                        "COMPLETE SEARCHABLE TEXT LINE 04")) {
                    content.showText(line);
                    content.newLineAtOffset(0, -180);
                }
                content.endText();
            }

            PDPage photoPage = new PDPage(PDRectangle.A4);
            pdf.addPage(photoPage);
            BufferedImage photo = new BufferedImage(800, 700, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = photo.createGraphics();
            graphics.setColor(new Color(30, 90, 190));
            graphics.fillRect(0, 0, photo.getWidth(), photo.getHeight());
            graphics.dispose();
            try (PDPageContentStream content = new PDPageContentStream(pdf, photoPage)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, photo), 30, 80, 535, 620);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(70, 730);
                content.showText("NATIVE TEXT WITH AN ORDINARY PHOTO 2026");
                content.endText();
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private Path createVectorOnlyPdf() throws Exception {
        Path source = temp.resolve("vector-only.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(240, 320));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.setNonStrokingColor(new Color(230, 240, 250));
                content.addRect(20, 30, 200, 260);
                content.fill();
                content.setStrokingColor(new Color(20, 80, 160));
                content.setLineWidth(4);
                content.moveTo(30, 60);
                content.lineTo(210, 260);
                content.stroke();
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private BufferedImage documentRaster(String value) {
        BufferedImage scan = new BufferedImage(1200, 1500, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scan.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, scan.getWidth(), scan.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 82));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString(value, 80, 280);
        graphics.drawString("CONTENT MUST SURVIVE", 80, 620);
        graphics.dispose();
        return scan;
    }

    private Path fakeTesseractWithoutText() throws Exception {
        Path binary = temp.resolve("fake-tesseract-no-text");
        String script = "#!/bin/sh\n"
                + "base=\"$2\"\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n";
        Files.writeString(binary, script);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    private Path fakeTesseractWithText(String text, String confidence) throws Exception {
        Path binary = temp.resolve("fake-tesseract-with-text-" + System.nanoTime());
        String script = "#!/bin/sh\n"
                + "base=\"$2\"\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"${base}.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t20\\t10\\t150\\t60\\t" + confidence
                + "\\t" + text + "\\n' >> \"${base}.tsv\"\n";
        Files.writeString(binary, script);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    private Path createTextOnlyPdf() throws Exception {
        Path source = temp.resolve("text-only.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                content.newLineAtOffset(70, 720);
                content.showText("NATIVE TEXT");
                content.endText();
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/pdf", Files.size(source), source);
    }
}
