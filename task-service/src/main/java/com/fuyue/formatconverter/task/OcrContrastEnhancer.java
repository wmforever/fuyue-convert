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
        PaperRows paper = new PaperRows(background, columns, rows, tile, width, height);
        long[] contrastHistogram = new long[256];
        for (int y = 0; y < height; y++) {
            paper.prepare(y);
            source.getRGB(0, y, width, 1, pixels, 0, width);
            for (int x = 0; x < width; x++) {
                contrastHistogram[paper.difference(x, luminance(pixels[x]))]++;
            }
        }
        long target = (long) Math.ceil((long) width * height * 0.99);
        long count = 0;
        int contrast = 0;
        for (; contrast < 255; contrast++) {
            count += contrastHistogram[contrast];
            if (count >= target) break;
        }
        if (contrast < 4) {
            // A short label can occupy less than 1% of a mostly blank page. Its ink
            // must not disappear into the whole-page paper percentile (or depend on
            // the host font's stroke density). Use the meaningful contrast tail only
            // when enough non-paper pixels remain to exclude isolated dirt.
            long inkPixels = 0;
            for (int value = 4; value < 256; value++) inkPixels += contrastHistogram[value];
            long minimumInk = Math.max(64L, ((long) width * height + 99_999L) / 100_000L);
            if (inkPixels < minimumInk) return null;
            long inkTarget = (long) Math.ceil(inkPixels * .90);
            long inkCount = 0;
            for (contrast = 4; contrast < 255; contrast++) {
                inkCount += contrastHistogram[contrast];
                if (inkCount >= inkTarget) break;
            }
        }
        contrast = Math.max(12, contrast);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] output = new byte[width];
        for (int y = 0; y < height; y++) {
            paper.prepare(y);
            source.getRGB(0, y, width, 1, pixels, 0, width);
            for (int x = 0; x < width; x++) {
                int delta = paper.difference(x, luminance(pixels[x]));
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

    /** Cache horizontal interpolation for two tile rows, never an image-sized surface.
     * Keep the original floating-point operation order, including edge extrapolation,
     * so optimization cannot alter faint ink or the sparse-ink percentile decision.
     */
    private static final class PaperRows {
        private final int[] background, left;
        private final int columns, rows, tile, height;
        private final double[] fractionX, upper, lower;
        private int cachedTop = -1;
        private double fractionY;

        PaperRows(int[] background, int columns, int rows, int tile, int width, int height) {
            this.background = background;
            this.columns = columns;
            this.rows = rows;
            this.tile = tile;
            this.height = height;
            left = new int[width];
            fractionX = new double[width];
            upper = new double[width];
            lower = new double[width];
            for (int x = 0; x < width; x++) {
                left[x] = Math.max(0, Math.min(columns - 2, (int) Math.floor((x + 0.5) / tile - 0.5)));
                fractionX[x] = fraction(x, left[x], Math.min(columns - 1, left[x] + 1), tile, width);
            }
        }

        void prepare(int y) {
            int top = Math.max(0, Math.min(rows - 2, (int) Math.floor((y + 0.5) / tile - 0.5)));
            int bottom = Math.min(rows - 1, top + 1);
            fractionY = fraction(y, top, bottom, tile, height);
            if (top == cachedTop) return;
            for (int x = 0; x < left.length; x++) {
                int right = Math.min(columns - 1, left[x] + 1);
                double fx = fractionX[x];
                upper[x] = background[top * columns + left[x]] * (1 - fx) + background[top * columns + right] * fx;
                lower[x] = background[bottom * columns + left[x]] * (1 - fx) + background[bottom * columns + right] * fx;
            }
            cachedTop = top;
        }

        int difference(int x, int gray) {
            return Math.max(0, Math.min(255, (int) Math.round(
                    upper[x] * (1 - fractionY) + lower[x] * fractionY) - gray));
        }
    }
}
