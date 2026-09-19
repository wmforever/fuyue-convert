package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfToTextConverterTest {
    @TempDir Path temp;

    @Test
    void extractsColumnsTopToBottomBeforeMovingRightAndPreservesPageBoundary() throws Exception {
        Path source = temp.resolve("columns.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4);
            document.addPage(first);
            addText(document, first, 50, 760, "LEFT-1");
            addText(document, first, 50, 720, "LEFT-2");
            addText(document, first, 350, 760, "RIGHT-1");
            addText(document, first, 350, 720, "RIGHT-2");
            PDPage second = new PDPage(PDRectangle.A4);
            document.addPage(second);
            addText(document, second, 50, 760, "PAGE-2");
            document.save(source.toFile());
        }
        Path output = temp.resolve("columns.txt");

        ConversionOutput converted = converter().convert(input(source), temp.resolve("columns-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(2, converted.pageCount());
        String text = Files.readString(output).replace("\r\n", "\n");
        assertEquals("LEFT-1\nLEFT-2\nRIGHT-1\nRIGHT-2\n\n\f\nPAGE-2\n", text);
    }

    @Test
    void imageOnlyPdfFailsWithOcrRequiredAndDoesNotCreateTxt() throws Exception {
        Path source = temp.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            addImagePage(document);
            document.save(source.toFile());
        }
        Path output = temp.resolve("scan.txt");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("scan-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_REQUIRED", failure.code());
        assertTrue(failure.getMessage().contains("第 1 页"));
        assertFalse(Files.exists(output));
    }

    @Test
    void mixedTextAndScannedPdfFailsInsteadOfDroppingScannedPage() throws Exception {
        Path source = temp.resolve("mixed.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage textPage = new PDPage(PDRectangle.A4);
            document.addPage(textPage);
            addText(document, textPage, 50, 760, "REAL-TEXT");
            addImagePage(document);
            document.save(source.toFile());
        }

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("mixed-work"), temp.resolve("mixed.txt"),
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_REQUIRED", failure.code());
        assertTrue(failure.getMessage().contains("第 2 页"));
        assertFalse(Files.exists(temp.resolve("mixed.txt")));
    }

    @Test
    void longNativeHeaderAndFullPageScanStillRequiresOcr() throws Exception {
        Path source = temp.resolve("same-page-mixed.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            BufferedImage scan = scannedBodyImage();
            try (PDPageContentStream content = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true)) {
                content.drawImage(LosslessFactory.createFromImage(document, scan), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            addText(document, page, 45, 790, "LONG NATIVE HEADER OR WATERMARK 2026");
            document.save(source.toFile());
        }
        Path output = temp.resolve("same-page-mixed.txt");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("same-page-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_REQUIRED", failure.code());
        assertTrue(failure.getMessage().contains("第 1 页"));
        assertFalse(Files.exists(output));
    }

    @Test
    void genuinelyBlankPageCanProduceEmptyTextWithoutPretendingItWasScanned() throws Exception {
        Path source = temp.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(source.toFile());
        }
        Path output = temp.resolve("blank.txt");

        ConversionOutput converted = converter().convert(input(source), temp.resolve("blank-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(1, converted.pageCount());
        assertTrue(Files.exists(output));
        assertEquals("", Files.readString(output));
    }

    @Test
    void unusedImageResourceDoesNotCauseFalseExtractionFailure() throws Exception {
        Path source = temp.resolve("unused-image-resource.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setResources(new PDResources());
            document.addPage(page);
            BufferedImage unused = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
            page.getResources().add(LosslessFactory.createFromImage(document, unused));
            addText(document, page, 50, 760, "ONLY REFERENCED TEXT");
            document.save(source.toFile());
        }
        Path output = temp.resolve("unused-image-resource.txt");

        ConversionOutput converted = converter().convert(input(source), temp.resolve("unused-resource-work"),
                output, ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(1, converted.pageCount());
        assertTrue(Files.readString(output).contains("ONLY REFERENCED TEXT"));
    }

    @Test
    void noTextPageWithTooManyDrawnRasterCandidatesReturnsStableLimitCode() throws Exception {
        Path source = temp.resolve("too-many-drawn-images.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            BufferedImage raster = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            var pdfImage = LosslessFactory.createFromImage(document, raster);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < 65; index++) {
                    content.drawImage(pdfImage, 0, 0,
                            page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            document.save(source.toFile());
        }
        Path output = temp.resolve("too-many-drawn-images.txt");

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("too-many-images-work"), output,
                        ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("OCR_IMAGE_LIMIT_EXCEEDED", failure.code());
        assertFalse(Files.exists(output));
    }

    @Test
    void passwordProtectedPdfReturnsStableErrorCode() throws Exception {
        Path source = temp.resolve("protected.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret",
                    new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(source.toFile());
        }

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> converter().convert(input(source), temp.resolve("protected-work"),
                        temp.resolve("protected.txt"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("PDF_PASSWORD_REQUIRED", failure.code());
        assertFalse(Files.exists(temp.resolve("protected.txt")));
    }

    private void addText(PDDocument document, PDPage page, float x, float y, String value) throws Exception {
        try (PDPageContentStream content = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(x, y);
            content.showText(value);
            content.endText();
        }
    }

    private void addImagePage(PDDocument document) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        BufferedImage image = new BufferedImage(500, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.drawString("scanned page", 200, 350);
        graphics.dispose();
        var pdfImage = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(pdfImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
        }
    }

    private BufferedImage scannedBodyImage() {
        BufferedImage image = new BufferedImage(1000, 1300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("SCANNED BODY 2026", 90, 240);
        graphics.drawString("CONTENT MUST SURVIVE", 90, 520);
        graphics.dispose();
        return image;
    }

    private PdfToTextConverter converter() {
        return new PdfToTextConverter(new PdfLayoutParser(), new PageLayoutAnalyzer());
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/pdf", Files.size(source), source);
    }
}
