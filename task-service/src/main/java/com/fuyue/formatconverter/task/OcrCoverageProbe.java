package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/** Eligibility evidence for one existing contrast retry, never a completeness certificate. */
final class OcrCoverageProbe {
    private static final int EDGE = 700, TILE = 32, MAX_BOX_VISITS = 2_000_000;

    private OcrCoverageProbe() { }

    static boolean hasUncoveredShadedInk(BufferedImage source, List<TextBlock> blocks,
                                         Rect physicalBox, long deadline) {
        if (System.nanoTime() >= deadline || !Double.isFinite(physicalBox.x() + physicalBox.y() + physicalBox.width() + physicalBox.height())
                || physicalBox.width() <= 0 || physicalBox.height() <= 0
                || source.getWidth() > 32768 || source.getHeight() > 32768) return false;
        double scale = Math.min(1d, EDGE / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = (int) Math.round(source.getWidth() * scale), height = (int) Math.round(source.getHeight() * scale);
        if (width < 96 || height < 96) return false;
        // RGB avoids the gamma conversion implicit in BYTE_GRAY raster samples.
        BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try {
            var graphics = thumb.createGraphics();
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null); graphics.dispose();
            int columns = (width + TILE - 1) / TILE, rows = (height + TILE - 1) / TILE;
            int[] paper = new int[columns * rows], histogram = new int[256];
            int darkest = 255, lightest = 0;
            for (int ty = 0; ty < rows; ty++) {
                if (System.nanoTime() >= deadline) return false;
                for (int tx = 0; tx < columns; tx++) {
                    java.util.Arrays.fill(histogram, 0);
                    int right = Math.min(width, (tx + 1) * TILE), bottom = Math.min(height, (ty + 1) * TILE);
                    for (int y = ty * TILE; y < bottom; y++) for (int x = tx * TILE; x < right; x++) {
                        histogram[luma(thumb.getRGB(x, y))]++;
                    }
                    int threshold = (int) Math.ceil((right - tx * TILE) * (bottom - ty * TILE) * .60);
                    int count = 0, value = 0;
                    while (value < 255 && (count += histogram[value]) < threshold) value++;
                    paper[ty * columns + tx] = value;
                    darkest = Math.min(darkest, value); lightest = Math.max(lightest, value);
                }
            }
            // Ordinary white paper and uniformly dark pages do not justify a new retry.
            if (lightest - darkest < 40 || lightest < 150 || darkest < 40) return false;
            boolean[] covered = new boolean[width * height];
            int visits = 0;
            for (TextBlock block : blocks) for (TextBlock.OcrWord word : block.ocrWords()) {
                if (System.nanoTime() >= deadline) return false;
                Rect box = word.box();
                if (!Double.isFinite(box.x() + box.y() + box.width() + box.height()) || box.width() < 0 || box.height() < 0) return false;
                int left = clamp((int) Math.floor((box.x() - physicalBox.x()) / physicalBox.width() * width) - 1, width);
                int top = clamp((int) Math.floor((box.y() - physicalBox.y()) / physicalBox.height() * height) - 1, height);
                int right = clamp((int) Math.ceil((box.x() + box.width() - physicalBox.x()) / physicalBox.width() * width) + 1, width);
                int bottom = clamp((int) Math.ceil((box.y() + box.height() - physicalBox.y()) / physicalBox.height() * height) + 1, height);
                int area = Math.max(0, right - left) * Math.max(0, bottom - top);
                if (area > MAX_BOX_VISITS - visits) return false;
                visits += area;
                for (int y = top; y < bottom; y++) java.util.Arrays.fill(covered, y * width + left, y * width + right, true);
            }
            int ink = 0, uncovered = 0, bands = 0, bandStart = -1, bandInk = 0, firstBand = height, lastBand = 0;
            for (int y = 0; y <= height; y++) {
                if (System.nanoTime() >= deadline) return false;
                int rowInk = 0;
                if (y < height) for (int x = 0; x < width; x++) {
                    if (paper[(y / TILE) * columns + x / TILE] - luma(thumb.getRGB(x, y)) < 25) continue;
                    ink++;
                    if (!covered[y * width + x]) { uncovered++; rowInk++; }
                }
                if (rowInk >= Math.max(8, width / 70)) {
                    if (bandStart < 0) bandStart = y;
                    bandInk += rowInk;
                } else if (bandStart >= 0) {
                    if (bandInk >= 50 && y - bandStart <= height / 10) {
                        bands++; firstBand = Math.min(firstBand, bandStart); lastBand = y;
                    }
                    bandStart = -1; bandInk = 0;
                }
            }
            return ink >= 200 && ink < width * height * .15 && uncovered > ink * .40
                    && bands >= 3 && lastBand - firstBand >= height * .25;
        } finally { thumb.flush(); }
    }

    private static int clamp(int value, int limit) { return Math.max(0, Math.min(limit, value)); }
    private static int luma(int rgb) {
        return (((rgb >> 16) & 255) * 299 + ((rgb >> 8) & 255) * 587 + (rgb & 255) * 114 + 500) / 1000;
    }
}
