package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OcrImageNormalizerTest {
    @Test
    void appliesAllExifOrientationsBeforeOcr() {
        BufferedImage source = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        int color = 1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) source.setRGB(x, y, color++);
        }

        assertPixels(OcrImageNormalizer.orient(source, 2), new int[][]{{2, 1}, {4, 3}, {6, 5}});
        assertPixels(OcrImageNormalizer.orient(source, 3), new int[][]{{6, 5}, {4, 3}, {2, 1}});
        assertPixels(OcrImageNormalizer.orient(source, 4), new int[][]{{5, 6}, {3, 4}, {1, 2}});
        assertPixels(OcrImageNormalizer.orient(source, 5), new int[][]{{1, 3, 5}, {2, 4, 6}});
        assertPixels(OcrImageNormalizer.orient(source, 6), new int[][]{{5, 3, 1}, {6, 4, 2}});
        assertPixels(OcrImageNormalizer.orient(source, 7), new int[][]{{6, 4, 2}, {5, 3, 1}});
        assertPixels(OcrImageNormalizer.orient(source, 8), new int[][]{{2, 4, 6}, {1, 3, 5}});
    }

    private void assertPixels(BufferedImage actual, int[][] expected) {
        assertEquals(expected.length, actual.getHeight());
        assertEquals(expected[0].length, actual.getWidth());
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < expected[y].length; x++) {
                assertEquals(expected[y][x], actual.getRGB(x, y) & 0x00ffffff,
                        "pixel at " + x + "," + y);
            }
        }
    }
}
