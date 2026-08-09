package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfToImageConverterTest {
    @TempDir Path temp;

    @Test
    void writesTransparentPngAtConfiguredDpiWithPhysicalMetadata() throws Exception {
        Path source = temp.resolve("transparent.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(72, 36));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.setNonStrokingColor(Color.RED);
                content.addRect(0, 0, 36, 36);
                content.fill();
            }
            pdf.save(source.toFile());
        }
        Path output = temp.resolve("transparent.png");

        new PdfToPngConverter(null, 200).convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        var image = ImageIO.read(output.toFile());
        assertEquals(200, image.getWidth());
        assertEquals(100, image.getHeight());
        assertTrue(((image.getRGB(175, 50) >>> 24) & 0xff) < 10,
                "blank PDF canvas should remain transparent");
        assertTrue(new Color(image.getRGB(25, 50), true).getRed() > 240);
        var metadata = ImageMetadataReader.read(output, DocumentFormat.PNG);
        assertTrue(metadata.embeddedDpi());
        assertEquals(200d, metadata.dpiX(), 0.1d);
    }

    @Test
    void rendersDeviceCmykPdfToRgbJpegAndWritesJfifDpi() throws Exception {
        Path source = temp.resolve("cmyk.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(72, 36));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.setNonStrokingColor(1f, 0f, 0f, 0f);
                content.addRect(0, 0, 72, 36);
                content.fill();
            }
            pdf.save(source.toFile());
        }
        Path output = temp.resolve("cmyk.jpg");

        new PdfToJpgConverter(null, 120).convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        var image = ImageIO.read(output.toFile());
        assertEquals(120, image.getWidth());
        assertEquals(60, image.getHeight());
        Color cyan = new Color(image.getRGB(60, 30));
        assertTrue(cyan.getGreen() > 150 && cyan.getBlue() > 200 && cyan.getRed() < 80, cyan.toString());
        var metadata = ImageMetadataReader.read(output, DocumentFormat.JPG);
        assertTrue(metadata.embeddedDpi());
        assertEquals(120d, metadata.dpiX(), 0.1d);
    }

    @Test
    void rejectsHugeRenderBeforeAllocatingPageBitmap() throws Exception {
        Path source = temp.resolve("huge.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(14_400, 14_400)));
            pdf.save(source.toFile());
        }

        Exception failure = assertThrows(Exception.class,
                () -> new PdfToPngConverter(null, 600).convert(input(source), temp.resolve("work"),
                        temp.resolve("huge.png"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertTrue(failure.getMessage().contains("渲染像素超过限制"), failure.getMessage());
    }

    @Test
    void includesPdfUserUnitInRenderPixelPreflight() throws Exception {
        Path source = temp.resolve("large-user-unit.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1_440, 1_440));
            page.setUserUnit(10f);
            pdf.addPage(page);
            pdf.save(source.toFile());
        }

        Exception failure = assertThrows(Exception.class,
                () -> new PdfToPngConverter(null, 600).convert(input(source), temp.resolve("work"),
                        temp.resolve("large-user-unit.png"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertTrue(failure.getMessage().contains("渲染像素超过限制"), failure.getMessage());
    }

    @Test
    void returnsDedicatedErrorForPasswordProtectedPdf() throws Exception {
        Path source = temp.resolve("protected.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret",
                    new AccessPermission());
            policy.setEncryptionKeyLength(128);
            pdf.protect(policy);
            pdf.save(source.toFile());
        }

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new PdfToPngConverter(null, 160).convert(input(source), temp.resolve("work"),
                        temp.resolve("protected.png"), ParseLimits.defaults(), (stage, percent) -> { }));

        assertEquals("PDF_PASSWORD_REQUIRED", failure.code());
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/pdf", Files.size(source), source);
    }
}
