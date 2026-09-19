package com.fuyue.formatconverter.model;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannedContentDetectorTest {
    private static final Rect PAGE = new Rect(0, 0, 100, 100);

    @Test
    void largeDocumentRasterWithSparseNativeTextRequiresOcr() throws Exception {
        ImageBlock scan = image(documentRaster(), "SCAN");

        assertEquals(List.of(scan), ScannedContentDetector.imagesRequiringOcr(
                List.of(text("HDR")), List.of(scan), PAGE));
    }

    @Test
    void distributedNativeTextLayerIsSufficientWithoutRelyingOnCharacterCountAlone() throws Exception {
        ImageBlock scan = image(documentRaster(), "SCAN");
        List<TextBlock> searchable = List.of(
                text("SEARCHABLE TEXT LAYER LINE ONE 2026", 8),
                text("SEARCHABLE TEXT LAYER LINE TWO 2026", 32),
                text("SEARCHABLE TEXT LAYER LINE THREE 2026", 58),
                text("SEARCHABLE TEXT LAYER LINE FOUR 2026", 84));

        assertTrue(ScannedContentDetector.imagesRequiringOcr(
                searchable, List.of(scan), PAGE).isEmpty());
    }

    @Test
    void longHeaderAloneDoesNotPretendToBeACompleteTextLayer() throws Exception {
        ImageBlock scan = image(documentRaster(), "SCAN");
        TextBlock longHeader = text("LONG SEARCHABLE HEADER OR WATERMARK WITH MORE THAN TWENTY CHARACTERS", 5);

        assertEquals(List.of(scan), ScannedContentDetector.imagesRequiringOcr(
                List.of(longHeader), List.of(scan), PAGE));
    }

    @Test
    void tiledDocumentImagesUseUnionCoverage() throws Exception {
        ImageBlock top = image(documentRaster(), "TOP", new Rect(0, 0, 100, 40));
        ImageBlock bottom = image(documentRaster(), "BOTTOM", new Rect(0, 60, 100, 40));

        assertEquals(List.of(top, bottom), ScannedContentDetector.imagesRequiringOcr(
                List.of(text("HEADER", 2)), List.of(top, bottom), PAGE));
    }

    @Test
    void ordinaryPhotoAndBlankBackgroundAreNotMistakenForScannedDocuments() throws Exception {
        ImageBlock photo = image(solid(new Color(30, 90, 190)), "PHOTO");
        ImageBlock blank = image(solid(Color.WHITE), "BACKGROUND");
        ImageBlock product = image(productSilhouette(), "PRODUCT");
        ImageBlock chart = image(barChart(), "CHART");

        assertTrue(ScannedContentDetector.imagesRequiringOcr(
                List.of(text("HDR")), List.of(photo, blank, product, chart), PAGE).isEmpty());
    }

    @Test
    void unreadableLargeRasterFailsClosed() {
        ImageBlock unreadable = new ImageBlock("broken", 1, PAGE, "image/png",
                new byte[]{1, 2, 3, 4}, "SCAN", 0);

        assertEquals(List.of(unreadable), ScannedContentDetector.imagesRequiringOcr(
                List.of(text("HDR")), List.of(unreadable), PAGE));
    }

    @Test
    void boundsRasterCandidatesBeforeScanningEvenCompleteTextLayers() throws Exception {
        ImageBlock template = image(documentRaster(), "TEMPLATE", PAGE);
        List<ImageBlock> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < 65; index++) {
            candidates.add(new ImageBlock("tile-" + index, 1, template.box(), template.mimeType(),
                    template.data(), "SCAN", index));
        }
        List<TextBlock> completeLayer = List.of(
                text("SEARCHABLE TEXT LAYER LINE ONE 2026", 8),
                text("SEARCHABLE TEXT LAYER LINE TWO 2026", 32),
                text("SEARCHABLE TEXT LAYER LINE THREE 2026", 58),
                text("SEARCHABLE TEXT LAYER LINE FOUR 2026", 84));

        assertThrows(ScannedContentDetector.AnalysisLimitException.class,
                () -> ScannedContentDetector.imagesRequiringOcr(
                        completeLayer, candidates, PAGE));
    }

    @Test
    void noTextPageStillEnforcesRasterCandidateLimit() throws Exception {
        ImageBlock template = image(documentRaster(), "TEMPLATE", new Rect(0, 0, 100, 10));
        List<ImageBlock> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < 65; index++) {
            candidates.add(new ImageBlock("tile-" + index, 1, template.box(), template.mimeType(),
                    template.data(), "SCAN", index));
        }

        assertThrows(ScannedContentDetector.AnalysisLimitException.class,
                () -> ScannedContentDetector.requiresOcr(List.of(), candidates, PAGE));
    }

    private TextBlock text(String value) {
        return text(value, 5);
    }

    private TextBlock text(String value, double y) {
        return new TextBlock("text-" + y, 1, new Rect(5, y, 90, 8), value, y + 7,
                new FontStyle("Sans", 10, false, false, ColorValue.BLACK), 1);
    }

    private ImageBlock image(BufferedImage raster, String role) throws Exception {
        return image(raster, role, PAGE);
    }

    private ImageBlock image(BufferedImage raster, String role, Rect box) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(raster, "png", bytes);
        return new ImageBlock("image-" + role, 1, box, "image/png", bytes.toByteArray(), role, 0);
    }

    private BufferedImage documentRaster() {
        BufferedImage raster = solid(Color.WHITE);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 54));
        graphics.drawString("SCANNED BODY", 50, 180);
        graphics.drawString("CONTENT", 50, 420);
        graphics.dispose();
        return raster;
    }

    private BufferedImage solid(Color color) {
        BufferedImage raster = new BufferedImage(800, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
        graphics.dispose();
        return raster;
    }

    private BufferedImage productSilhouette() {
        BufferedImage raster = solid(Color.WHITE);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(new Color(45, 45, 45));
        graphics.fillRoundRect(220, 180, 360, 640, 90, 90);
        graphics.setColor(Color.WHITE);
        graphics.fillOval(345, 230, 110, 110);
        graphics.dispose();
        return raster;
    }

    private BufferedImage barChart() {
        BufferedImage raster = solid(Color.WHITE);
        Graphics2D graphics = raster.createGraphics();
        graphics.setColor(new Color(55, 55, 55));
        graphics.drawLine(100, 820, 720, 820);
        graphics.drawLine(100, 120, 100, 820);
        for (int index = 0; index < 6; index++) {
            int height = 120 + index * 75;
            graphics.fillRect(145 + index * 90, 820 - height, 45, height);
        }
        graphics.dispose();
        return raster;
    }
}
