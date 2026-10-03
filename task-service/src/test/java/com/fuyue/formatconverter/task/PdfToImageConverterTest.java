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
import java.util.Optional;
import java.util.List;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void taskDpiOverridesTheConverterDefaultAndIsValidatedBeforeRendering() throws Exception {
        Path source = temp.resolve("task-dpi.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(72, 36)));
            pdf.save(source.toFile());
        }
        Path output = temp.resolve("task-dpi.png");
        ConversionOptions options = ConversionOptions.fromRequest(null, null, null, null,
                null, null, null, null, null, 72);
        new PdfToPngConverter(null, 200).convert(
                new ConversionInput("task-dpi.pdf", "application/pdf", Files.size(source), source, options),
                temp.resolve("task-dpi-work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        var image = ImageIO.read(output.toFile());
        assertEquals(72, image.getWidth());
        assertEquals(36, image.getHeight());
        assertEquals(72d, ImageMetadataReader.read(output, DocumentFormat.PNG).dpiX(), 0.1d);
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

    @Test
    void discoversBundledPopplerFromAppHome() throws Exception {
        String property = "format.converter.app.home";
        String previous = System.getProperty(property);
        Path appHome = temp.resolve("runtime-home");
        Path popplerDir = Files.createDirectories(appHome.resolve("app/poppler/bin"));
        Path binary = popplerDir.resolve("pdftoppm");
        Files.writeString(binary, "#!/bin/sh\nexit 0\n");
        binary.toFile().setExecutable(true);
        try {
            System.setProperty(property, appHome.toString());
            Optional<Path> discovered = PdfToImageConverter.discoverPoppler();
            assertTrue(discovered.isPresent());
            assertEquals(binary.toAbsolutePath().normalize(), discovered.orElseThrow());
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/pdf", Files.size(source), source);
    }

    @Test
    void selectedPngPagesKeepSourceNumbersOrderAndPixelsWithoutRenderingOtherPages() throws Exception {
        verifySelectedPages(new PdfToPngConverter(null, 72), "png");
    }

    @Test
    void selectedJpegPagesWorkWithJavaFallback() throws Exception {
        verifySelectedPages(new PdfToJpgConverter(null, 72), "jpg");
    }

    @Test
    void selectedJpegPagesWorkWithRealPoppler() throws Exception {
        Optional<Path> binary = PdfToImageConverter.discoverPoppler();
        assumeTrue(binary.isPresent(), "需要实际 Poppler 验证选中页渲染");
        verifySelectedPages(new PdfToJpgConverter(binary.orElseThrow(), 72), "jpg");
    }

    private void verifySelectedPages(FileConverter converter, String extension) throws Exception {
        Path source = coloredPages();
        ConversionInput input = selectedInput(source, "10,2-3,2");
        ConversionOutput output = converter.convert(input, temp.resolve("selected-" + extension),
                temp.resolve("selected." + extension), ParseLimits.defaults(), (stage, percent) -> { });
        assertEquals(3, output.pageCount());
        try (ZipFile zip = new ZipFile(output.path().toFile())) {
            assertEquals(List.of("page-0002." + extension, "page-0003." + extension, "page-0010." + extension),
                    zip.stream().map(entry -> entry.getName()).toList());
            for (int page : List.of(2, 3, 10)) {
                try (var stream = zip.getInputStream(zip.getEntry("page-%04d.%s".formatted(page, extension)))) {
                    var image = ImageIO.read(stream);
                    assertEquals(72, image.getWidth());
                    Color actual = new Color(image.getRGB(36, 36));
                    Color expected = pageColor(page);
                    assertTrue(Math.abs(actual.getRed() - expected.getRed()) < 20);
                    assertTrue(Math.abs(actual.getGreen() - expected.getGreen()) < 20);
                    assertTrue(Math.abs(actual.getBlue() - expected.getBlue()) < 20);
                }
            }
        }
    }

    @Test
    void selectingOnePageReturnsImageNamedForOriginalPageAndRejectsOverflow() throws Exception {
        Path source = coloredPages();
        ConversionOutput result = new PdfToPngConverter(null, 72).convert(selectedInput(source, "10"),
                temp.resolve("single"), temp.resolve("single.png"), ParseLimits.defaults(), (stage, percent) -> { });
        assertEquals("colored-page-0010.png", result.outputName());
        assertEquals(1, result.pageCount());
        assertEquals(72, ImageIO.read(result.path().toFile()).getWidth());
        Path badOutput = temp.resolve("overflow.png");
        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> new PdfToPngConverter(null, 72).convert(selectedInput(source, "1,13"),
                        temp.resolve("overflow"), badOutput, ParseLimits.defaults(), (stage, percent) -> { }));
        assertEquals("PDF_PAGE_RANGE_INVALID", error.code());
        assertFalse(Files.exists(badOutput));
        Exception huge = assertThrows(Exception.class, () -> new PdfToPngConverter(null, 72).convert(
                selectedInput(source, "1"), temp.resolve("huge-selected"), temp.resolve("huge-selected.png"),
                ParseLimits.defaults(), (stage, percent) -> { }));
        assertTrue(huge.getMessage().contains("渲染像素超过限制"));
    }

    private Path coloredPages() throws Exception {
        Path source = temp.resolve("colored.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 1; i <= 12; i++) {
                // Page 1 would exceed the bitmap limit and must never be rendered for a 2,3,10 selection.
                PDPage page = new PDPage(i == 1 ? new PDRectangle(14_400, 14_400) : new PDRectangle(72, 72));
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.setNonStrokingColor(pageColor(i));
                    content.addRect(0, 0, 72, 72);
                    content.fill();
                }
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private Color pageColor(int page) {
        return new Color[]{Color.RED, Color.GREEN, Color.BLUE, Color.ORANGE}[(page - 1) % 4];
    }

    private ConversionInput selectedInput(Path source, String pages) throws Exception {
        ConversionOptions options = ConversionOptions.fromRequest(null, null, null, null, null,
                null, null, null, null, null, pages, null, null);
        return new ConversionInput("colored.pdf", "application/pdf", Files.size(source), source, options);
    }
}
