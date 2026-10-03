package com.fuyue.formatconverter.task;

import java.awt.image.BufferedImage;

/** Removes slowly varying paper shading without resampling or moving any pixel. */
final class OcrContrastEnhancer {
    private OcrContrastEnhancer() { }

    static BufferedImage enhance(BufferedImage source) {
        int width = source.getWidth(), height = source.getHeight();
        if (width > 32768 || height > 32768) return null; // Bound optional scratch space for extremely narrow images.
        int tile = Math.max(32, Math.min(128, Math.min(width, height) / 8));
        int columns = (width + tile - 1) / tile, rows = (height + tile - 1) / tile;
        int[] background = new int[columns * rows];
        int[] pixels = new int[width];
        // Tile histograms keep memory proportional to image width, not image area.
        for (int row = 0; row < rows; row++) {
            int[][] histograms = new int[columns][256];
            int bottom = Math.min(height, (row + 1) * tile);
            for (int y = row * tile; y < bottom; y++) {
                source.getRGB(0, y, width, 1, pixels, 0, width);
                for (int x = 0; x < width; x++) histograms[x / tile][luminance(pixels[x])]++;
            }
            for (int column = 0; column < columns; column++) {
                int count = (Math.min(width, (column + 1) * tile) - column * tile) * (bottom - row * tile);
                background[row * columns + column] = percentile(histograms[column], (int) Math.ceil(count * 0.60));
            }
        }
        long[] contrastHistogram = new long[256];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, pixels, 0, width);
            for (int x = 0; x < width; x++) {
                contrastHistogram[difference(background, columns, rows, tile, width, height, x, y, luminance(pixels[x]))]++;
            }
        }
        long target = (long) Math.ceil((long) width * height * 0.99);
        long count = 0;
        int contrast = 0;
        for (; contrast < 255; contrast++) {
            count += contrastHistogram[contrast];
            if (count >= target) break;
        }
        if (contrast < 4) return null; // Blank/near-uniform pages do not merit another OCR process.
        contrast = Math.max(12, contrast);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] output = new byte[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, pixels, 0, width);
            for (int x = 0; x < width; x++) {
                int delta = difference(background, columns, rows, tile, width, height, x, y, luminance(pixels[x]));
                output[x] = (byte) (255 - Math.min(255, Math.max(0, delta - 2) * 255 / contrast));
            }
            result.getRaster().setDataElements(0, y, width, 1, output);
        }
        return result;
    }

    private static int luminance(int rgba) {
        int gray = (((rgba >>> 16) & 255) * 299 + ((rgba >>> 8) & 255) * 587 + (rgba & 255) * 114 + 500) / 1000;
        int alpha = (rgba >>> 24) & 255;
        return (gray * alpha + 255 * (255 - alpha) + 127) / 255;
    }

    private static int percentile(int[] histogram, int target) {
        int count = 0;
        for (int value = 0; value < 256; value++) {
            count += histogram[value];
            if (count >= target) return value;
        }
        return 255;
    }

    private static double fraction(int position, int lower, int upper, int tile, int extent) {
        if (lower == upper) return 0;
        double firstCenter = (lower * tile + Math.min(extent, (lower + 1) * tile)) / 2.0;
        double secondCenter = (upper * tile + Math.min(extent, (upper + 1) * tile)) / 2.0;
        // Extrapolate half a tile at edges so a smooth shadow does not become a dark border.
        return (position + 0.5 - firstCenter) / (secondCenter - firstCenter);
    }

    private static int difference(int[] background, int columns, int rows, int tile, int width, int height, int x, int y, int gray) {
        int left = Math.max(0, Math.min(columns - 2, (int) Math.floor((x + 0.5) / tile - 0.5)));
        int top = Math.max(0, Math.min(rows - 2, (int) Math.floor((y + 0.5) / tile - 0.5)));
        int right = Math.min(columns - 1, left + 1), bottom = Math.min(rows - 1, top + 1);
        double fx = fraction(x, left, right, tile, width), fy = fraction(y, top, bottom, tile, height);
        double upper = background[top * columns + left] * (1 - fx) + background[top * columns + right] * fx;
        double lower = background[bottom * columns + left] * (1 - fx) + background[bottom * columns + right] * fx;
        return Math.max(0, Math.min(255, (int) Math.round(upper * (1 - fy) + lower * fy) - gray));
    }
}
