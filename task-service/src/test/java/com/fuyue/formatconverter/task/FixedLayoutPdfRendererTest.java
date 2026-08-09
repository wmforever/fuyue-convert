package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.LineElement;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Point;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedLayoutPdfRendererTest {
    @TempDir Path temp;

    @Test void rendersPageGeometryEditableTextImagesAndLinesAtSourceCoordinates() throws Exception {
        byte[] redImage = png(Color.RED);
        PageModel first = new PageModel(1, new Rect(0, 0, 100, 60),
                List.of(new TextBlock("text-1", 1, new Rect(10, 8, 70, 10),
                        "固定版式 Editable 123", 15,
                        new FontStyle("SourceFont", 12, false, false, ColorValue.BLACK), 3)),
                List.of(new LineElement("line-1", 1, new Point(10, 40), new Point(90, 40),
                        1, new ColorValue(0, 0, 255, 255), 1)),
                List.of(new ImageBlock("image-1", 1, new Rect(20, 20, 20, 10),
                        "image/png", redImage, "IMAGE", 2)), List.of(), List.of(), List.of());
        PageModel second = new PageModel(2, new Rect(0, 0, 60, 100),
                List.of(new TextBlock("text-2", 2, new Rect(8, 10, 45, 10),
                        "第二页文字", 17, FontStyle.defaults(), 1)),
                List.of(), List.of(), List.of(), List.of(), List.of());
        DocumentModel model = new DocumentModel("layout.ofd", "test", 2,
                List.of(first, second), List.of());
        Path output = temp.resolve("fixed-layout.pdf");

        new FixedLayoutPdfRenderer().render(model, output);

        try (PDDocument pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(100d * 72d / 25.4d, pdf.getPage(0).getMediaBox().getWidth(), 0.02d);
            assertEquals(60d * 72d / 25.4d, pdf.getPage(0).getMediaBox().getHeight(), 0.02d);
            assertEquals(60d * 72d / 25.4d, pdf.getPage(1).getMediaBox().getWidth(), 0.02d);
            assertEquals(100d * 72d / 25.4d, pdf.getPage(1).getMediaBox().getHeight(), 0.02d);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("固定版式"), text);
            assertTrue(text.contains("Editable 123"), text);
            assertTrue(text.contains("第二页文字"), text);
            long images = stream(pdf.getPage(0).getResources().getXObjectNames())
                    .filter(name -> {
                        try {
                            return pdf.getPage(0).getResources().getXObject(name) instanceof PDImageXObject;
                        } catch (Exception ignored) {
                            return false;
                        }
                    }).count();
            assertEquals(1, images);

            BufferedImage rendered = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertColorNear(Color.RED, rendered.getRGB(points(30), points(25)), 10);
            assertColorNear(Color.BLUE, rendered.getRGB(points(50), points(40)), 20);
        }
    }

    private java.util.stream.Stream<org.apache.pdfbox.cos.COSName> stream(
            Iterable<org.apache.pdfbox.cos.COSName> values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false);
    }

    private byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private int points(double millimetres) {
        return (int) Math.round(millimetres * 72d / 25.4d);
    }

    private void assertColorNear(Color expected, int rgb, int tolerance) {
        Color actual = new Color(rgb);
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance
                        && Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance
                        && Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance,
                () -> "expected " + expected + " but was " + actual);
    }
}
