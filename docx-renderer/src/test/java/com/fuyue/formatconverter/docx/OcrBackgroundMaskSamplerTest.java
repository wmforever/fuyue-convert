package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.Rect;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OcrBackgroundMaskSamplerTest {
    private static final Rect PAGE = new Rect(10, 20, 100, 80);
    private static final Rect WORD = new Rect(30, 45, 35, 8);

    @Test void matchesUniformGrayAndTintedPaperWithoutReadingTheTextInkAsPaper() throws Exception {
        for (Color paper : List.of(new Color(160, 160, 160), new Color(222, 208, 165))) {
            BufferedImage pixels = image(paper);
            var graphics = pixels.createGraphics(); graphics.setColor(Color.BLACK);
            graphics.fillRect(105, 135, 12, 23); graphics.fillRect(155, 135, 12, 23); graphics.dispose();
            var source = source(pixels);
            try (var sampler = OcrBackgroundMaskSampler.open(source)) {
                var fills = sampler.fills(WORD);
                assertEquals(1, fills.size());
                assertEquals(WORD, fills.get(0).box());
                assertEquals("%02X%02X%02X".formatted(paper.getRed(), paper.getGreen(), paper.getBlue()), fills.get(0).color());
            }
        }
    }

    @Test void followsTwoDimensionalShadeWithBoundedPiecesAndNoCoordinateExpansion() throws Exception {
        BufferedImage pixels = new BufferedImage(500, 400, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) for (int x = 0; x < 500; x++) {
            int gray = 70 + x / 4 + y / 10;
            pixels.setRGB(x, y, new Color(gray, gray, gray).getRGB());
        }
        try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
            var fills = sampler.fills(WORD);
            assertTrue(fills.size() > 1 && fills.size() <= 8);
            double area = 0;
            for (var fill : fills) {
                Rect cell = fill.box();
                assertTrue(WORD.contains(new com.fuyue.formatconverter.model.Point(cell.x(), cell.y()), .00001));
                assertTrue(WORD.contains(new com.fuyue.formatconverter.model.Point(cell.right(), cell.bottom()), .00001));
                double px = (cell.center().x() - PAGE.x()) * 5;
                double py = (cell.center().y() - PAGE.y()) * 5;
                int expected = 70 + (int) px / 4 + (int) py / 10;
                int gray = Integer.parseInt(fill.color().substring(0, 2), 16);
                assertEquals(expected, gray, 2);
                area += cell.width() * cell.height();
            }
            assertEquals(WORD.width() * WORD.height(), area, .00001);
        }
    }

    @Test void distinguishesWhiteInkOnBlackPaperFromColouredMarks() throws Exception {
        BufferedImage pixels = image(Color.BLACK);
        var graphics = pixels.createGraphics(); graphics.setColor(Color.WHITE);
        graphics.fillRect(105, 135, 12, 23); graphics.dispose();
        try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
            var fills = sampler.fills(WORD);
            assertEquals(1, fills.size());
            assertEquals("000000", fills.get(0).color());
        }
    }

    @Test void preservesColouredStampCrossingTheBorderOrInsideTheWord() throws Exception {
        for (boolean border : List.of(true, false)) {
            BufferedImage pixels = image(new Color(160, 160, 160));
            var graphics = pixels.createGraphics(); graphics.setColor(Color.RED);
            graphics.fillRect(150, border ? 122 : 138, 15, 15); graphics.dispose();
            try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
                assertTrue(sampler.fills(WORD).isEmpty(), "彩色印章采样应阻止覆盖源图");
            }
        }
    }

    @Test void blueBlackScanFringeIsInkWhileSaturatedBlueAndRedRemainProtected() throws Exception {
        for (Color ink : List.of(new Color(25, 20, 115), new Color(0, 0, 180),
                new Color(10, 10, 160), new Color(130, 15, 20), Color.RED)) {
            BufferedImage pixels = image(Color.WHITE);
            var g = pixels.createGraphics(); g.setColor(ink);
            g.fillRect(105, 130, 14, 25);
            if (ink.getBlue() == 115) { g.setColor(Color.BLACK); g.fillRect(107, 132, 10, 21); }
            g.dispose();
            try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
                assertEquals(ink.getBlue() == 115, !sampler.fills(WORD).isEmpty(), ink.toString());
            }
        }
    }

    @Test void lightCompressionFringeRequiresNearbyBlackInk() throws Exception {
        for (boolean hasInk : List.of(true, false)) {
            BufferedImage pixels = image(Color.WHITE);
            var g = pixels.createGraphics();
            g.setColor(new Color(250, 255, 210)); g.fillRect(105, 130, 14, 25);
            g.setColor(new Color(189, 179, 240)); g.fillRect(145, 130, 14, 25);
            if (hasInk) { g.setColor(Color.BLACK); g.fillRect(107, 132, 10, 21); g.fillRect(147, 132, 10, 21); }
            g.dispose();
            try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
                assertEquals(hasInk, !sampler.fills(WORD).isEmpty());
            }
        }
    }

    @Test void textCoversCannotEraseLongSourceDiagramRulesButCellInteriorRemainsEditable() throws Exception {
        for (boolean horizontal : List.of(true, false)) {
            BufferedImage pixels = image(Color.WHITE);
            var g = pixels.createGraphics(); g.setColor(Color.BLACK);
            if (horizontal) g.fillRect(40, 145, 340, 2); else g.fillRect(180, 40, 2, 300);
            g.dispose();
            try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
                assertTrue(sampler.fills(WORD).isEmpty());
                assertFalse(sampler.fills(new Rect(35, 35, 6, 4)).isEmpty());
            }
        }
    }

    @Test void preservesRuleWhereTextHidesItsSideMarginsAndCompressionLeavesShortGaps() throws Exception {
        BufferedImage pixels = image(Color.WHITE);
        var g = pixels.createGraphics(); g.setColor(Color.BLACK);
        g.fillRect(180, 40, 2, 300); g.fillRect(169, 137, 24, 18);
        g.setColor(Color.WHITE); g.fillRect(180, 118, 2, 2); g.dispose();
        try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
            assertTrue(sampler.fills(WORD).isEmpty());
        }
    }

    @Test void skipsTexturedAndAbruptlyChangingPaperInsteadOfCoveringItWhite() throws Exception {
        BufferedImage pixels = new BufferedImage(500, 400, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) for (int x = 0; x < 500; x++) {
            int gray = ((x / 7 + y / 7) % 2 == 0) ? 90 : 220;
            pixels.setRGB(x, y, new Color(gray, gray, gray).getRGB());
        }
        try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
            assertTrue(sampler.fills(WORD).isEmpty());
        }
    }

    @Test void cannotGuessPaperWhenTheWordCoversTheWholeImage() throws Exception {
        try (var sampler = OcrBackgroundMaskSampler.open(source(image(Color.WHITE)))) {
            assertTrue(sampler.fills(PAGE).isEmpty());
        }
    }

    @Test void crowdedNeighborInkAtSidesDoesNotPreventCleanWhiteWordMask() throws Exception {
        BufferedImage pixels = image(Color.WHITE);
        var g = pixels.createGraphics(); g.setColor(Color.BLACK);
        // Neighbor ink is outside the word's horizontal bounds; top/bottom
        // samples still establish paper without enlarging the erase region.
        g.fillRect(98, 126, 2, 34); g.fillRect(275, 126, 2, 8); g.dispose();
        try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
            var fills = sampler.fills(WORD);
            assertEquals(1, fills.size());
            assertEquals(WORD, fills.get(0).box());
            assertEquals("FFFFFF", fills.get(0).color());
        }
    }

    @Test void compositesTransparentPaperAgainstWhiteAndHandlesAClippedImageEdge() throws Exception {
        BufferedImage transparent = new BufferedImage(500, 400, BufferedImage.TYPE_INT_ARGB);
        try (var sampler = OcrBackgroundMaskSampler.open(source(transparent))) {
            var fills = sampler.fills(new Rect(PAGE.x(), PAGE.y(), 25, 8));
            assertEquals(1, fills.size());
            assertEquals("FFFFFF", fills.get(0).color());
        }
    }

    @Test void rejectsUnusableImageDimensionsOrUnknownEncoding() throws Exception {
        assertNull(OcrBackgroundMaskSampler.open(new ImageBlock("invalid", 1, PAGE, "image/png",
                new byte[]{1,2,3}, "OCR_SCAN_BACKGROUND", 0)));
        BufferedImage tiny = image(Color.WHITE);
        var valid = source(tiny);
        assertNull(OcrBackgroundMaskSampler.open(new ImageBlock("zero", 1, new Rect(10, 20, 0, 80),
                "image/png", valid.data(), "OCR_SCAN_BACKGROUND", 0)));
    }

    private BufferedImage image(Color paper) {
        BufferedImage image = new BufferedImage(500, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(paper); graphics.fillRect(0, 0, 500, 400); graphics.dispose();
        return image;
    }

    @Test void editReserveChecksEveryPixelIncludingSingleDarkOrColoredMarks() throws Exception {
        for (int mark : List.of(0xffffff, 0xeeeeee, 0xff0000)) {
            BufferedImage pixels = image(Color.WHITE);
            pixels.setRGB(157, 147, mark);
            try (var sampler = OcrBackgroundMaskSampler.open(source(pixels))) {
                assertEquals(mark == 0xffffff, sampler.uniformLightPaper(WORD, "FFFFFF"));
            }
        }
    }

    @Test void editReserveRejectsDarkPaperOutOfBoundsAndExhaustedPixelBudget() throws Exception {
        try (var sampler = OcrBackgroundMaskSampler.open(source(image(Color.WHITE)))) {
            assertFalse(sampler.uniformLightPaper(WORD, "404040"));
            assertFalse(sampler.uniformLightPaper(new Rect(0, 0, 10, 10), "FFFFFF"));
            assertTrue(sampler.uniformLightPaper(PAGE, "FFFFFF"));
            assertFalse(sampler.uniformLightPaper(PAGE, "FFFFFF"), "250000 pixel budget is cumulative per decoded image");
        }
        try (var sampler = OcrBackgroundMaskSampler.open(source(image(new Color(226, 226, 226))))) {
            assertTrue(sampler.uniformLightPaper(WORD, "E2E2E2"));
        }
    }

    private ImageBlock source(BufferedImage pixels) throws Exception {
        try {
            ByteArrayOutputStream data = new ByteArrayOutputStream(); assertTrue(ImageIO.write(pixels, "png", data));
            return new ImageBlock("scan", 1, PAGE, "image/png", data.toByteArray(), "OCR_SCAN_BACKGROUND", 0);
        } finally { pixels.flush(); }
    }
}
