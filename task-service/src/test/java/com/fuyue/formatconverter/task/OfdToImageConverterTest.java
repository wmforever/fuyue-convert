package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfdToImageConverterTest {
    @TempDir Path temp;

    @Test
    void rendersMultiPageOfdAsNumberedPngZipWithSourcePageSizes() throws Exception {
        Path blue = raster("blue.png", Color.BLUE);
        Path source = temp.resolve("multi.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(textPage(100d, 50d, "第一页文字"));
            Img image = new Img(60d, 90d, blue);
            image.setPosition(Position.Absolute).setBox(0d, 0d, 60d, 90d);
            document.addVPage(new VirtualPage(60d, 90d).add(image));
        }

        ConversionOutput converted = pngConverter().convert(input(source), temp.resolve("png-work"),
                temp.resolve("multi.png"), ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(2, converted.pageCount());
        assertEquals("multi-pages.zip", converted.outputName());
        assertEquals("multi.zip", converted.path().getFileName().toString());
        assertTrue(converted.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.FONT_SUBSTITUTED));
        try (ZipFile zip = new ZipFile(converted.path().toFile())) {
            assertEquals(List.of("page-0001.png", "page-0002.png"), zip.stream().map(entry -> entry.getName()).toList());
            BufferedImage first = ImageIO.read(zip.getInputStream(zip.getEntry("page-0001.png")));
            BufferedImage second = ImageIO.read(zip.getInputStream(zip.getEntry("page-0002.png")));
            assertDimensions(first, 630, 315);
            assertDimensions(second, 378, 567);
            assertTrue(hasNonWhitePixel(first));
            assertTrue(second.getRGB(second.getWidth() / 2, second.getHeight() / 2) != Color.WHITE.getRGB());
        }
    }

    @Test
    void rendersSingleImageOnlyOfdAsRealJpegWithoutRequiringOcr() throws Exception {
        Path red = raster("red.png", Color.RED);
        Path source = temp.resolve("scan.ofd");
        Img image = new Img(80d, 60d, red);
        image.setPosition(Position.Absolute).setBox(0d, 0d, 80d, 60d);
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(new VirtualPage(80d, 60d).add(image));
        }
        Path output = temp.resolve("scan.jpg");

        ConversionOutput converted = jpgConverter().convert(input(source), temp.resolve("jpg-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(output, converted.path());
        assertEquals("scan.jpg", converted.outputName());
        assertEquals(1, converted.pageCount());
        assertFalse(converted.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_REQUIRED));
        BufferedImage jpeg = ImageIO.read(output.toFile());
        assertNotNull(jpeg);
        assertDimensions(jpeg, 504, 378);
        Color center = new Color(jpeg.getRGB(jpeg.getWidth() / 2, jpeg.getHeight() / 2));
        assertTrue(center.getRed() > 180 && center.getGreen() < 80 && center.getBlue() < 80, center.toString());
    }

    private VirtualPage textPage(double width, double height, String value) {
        Paragraph paragraph = new Paragraph(value, 5d);
        paragraph.setPosition(Position.Absolute).setBox(10d, 10d, width - 20d, 15d);
        return new VirtualPage(width, height).add(paragraph);
    }

    private Path raster(String name, Color color) throws Exception {
        Path path = temp.resolve(name);
        BufferedImage image = new BufferedImage(240, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private boolean hasNonWhitePixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                if ((image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff) return true;
            }
        }
        return false;
    }

    private void assertDimensions(BufferedImage image, int width, int height) {
        assertEquals(width, image.getWidth(), 1);
        assertEquals(height, image.getHeight(), 1);
    }

    private OfdToPngConverter pngConverter() {
        return new OfdToPngConverter(new SafeOfdExtractor(), new OfdrwParser());
    }

    private OfdToJpgConverter jpgConverter() {
        return new OfdToJpgConverter(new SafeOfdExtractor(), new OfdrwParser());
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/ofd", Files.size(source), source);
    }
}
