package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfOcrConverterTest {
    @TempDir Path temp;

    @Test
    void fillsOnlyScannedPageForTxtAndEditableDocxWithoutEmbeddingPageImage() throws Exception {
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
            assertTrue(word.getAllPictures().isEmpty(), "OCR DOCX must contain editable text, not page images");
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
