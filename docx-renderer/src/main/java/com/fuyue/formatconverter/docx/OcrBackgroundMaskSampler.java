package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.Rect;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Estimates smooth paper from outside a word box; uncertain samples leave the scan exposed. */
final class OcrBackgroundMaskSampler implements AutoCloseable {
    private final BufferedImage image;
    private final Rect background;
    private final double sx, sy;
    private int blankPixelBudget = 250_000;
    private long rulePixelBudget = 80_000_000L;

    private OcrBackgroundMaskSampler(BufferedImage image, Rect background) {
        this.image = image;
        this.background = background;
        sx = image.getWidth() / background.width();
        sy = image.getHeight() / background.height();
    }

    static OcrBackgroundMaskSampler open(ImageBlock source) throws IOException {
        if (source.box().width() <= 0 || source.box().height() <= 0) return null;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(source.data()))) {
            if (input == null) return null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                // This optional visual refinement must not add unbounded decoded-image memory.
                if (width < 1 || height < 1 || width > 32768 || height > 32768
                        || (long) width * height > 25_000_000L) return null;
                BufferedImage pixels = reader.read(0);
                return pixels == null ? null : new OcrBackgroundMaskSampler(pixels, source.box());
            } finally { reader.dispose(); }
        }
    }

    List<Fill> fills(Rect mask) {
        if (mask.width() <= 0 || mask.height() <= 0) return List.of();
        double x = (mask.x() - background.x()) * sx, y = (mask.y() - background.y()) * sy;
        double width = mask.width() * sx, height = mask.height() * sy;
        List<Sample> samples = new ArrayList<>();
        int top = (int) Math.floor(y) - 1, bottom = (int) Math.ceil(y + height);
        int left = (int) Math.floor(x) - 1, right = (int) Math.ceil(x + width);
        for (int part = 0; part < 12; part++) {
            int horizontal = (int) Math.floor(x + width * (part + .5) / 12);
            int vertical = (int) Math.floor(y + height * (part + .5) / 12);
            addSample(samples, horizontal, top, x, y, width, height);
            addSample(samples, horizontal, bottom, x, y, width, height);
            addSample(samples, left, vertical, x, y, width, height);
            addSample(samples, right, vertical, x, y, width, height);
        }
        if (samples.size() < 12) return List.of();
        int[] median = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            int index = channel;
            int[] values = samples.stream().mapToInt(sample -> sample.color()[index]).sorted().toArray();
            median[channel] = values[values.length / 2];
        }
        // Coloured marks crossing the sampling ring are not paper that can safely be erased.
        for (Sample sample : samples) {
            int[] color = sample.color();
            if (Math.abs((color[0] - color[1]) - (median[0] - median[1])) > 24
                    || Math.abs((color[1] - color[2]) - (median[1] - median[2])) > 24) {
                int px = (int) Math.floor(x + width * (sample.u() + .5));
                int py = (int) Math.floor(y + height * (sample.v() + .5));
                if (!scanFringe(color, px, py)) return List.of();
            }
        }
        double medianBrightness = brightness(median);
        if (medianBrightness >= 110 && crossesSourceRule(x, y, width, height)) return List.of();
        double paperFloor = medianBrightness >= 245 ? medianBrightness - 12 : medianBrightness - 40;
        List<Sample> paper = samples.stream().filter(sample -> brightness(sample.color()) >= paperFloor)
                .filter(sample -> paperChroma(sample.color(), median)).toList();
        if (paper.size() < samples.size() * .8) {
            // Adjacent bold glyphs can touch the side ring even when both the
            // top and bottom demonstrate clean white paper. Use those edges
            // only for a near-white scan, never for shaded or textured paper.
            List<Sample> horizontalPaper = samples.stream()
                    .filter(sample -> Math.abs(sample.v()) >= .5d)
                    .filter(sample -> brightness(sample.color()) >= medianBrightness - 20)
                    .filter(sample -> paperChroma(sample.color(), median)).toList();
            if (medianBrightness < 245 || paper.size() < samples.size() * .55
                    || horizontalPaper.size() < 20) return List.of();
        }
        double[][] plane = fit(paper);
        if (plane == null) return List.of();
        for (Sample sample : paper) {
            for (int channel = 0; channel < 3; channel++) {
                if (Math.abs(value(plane[channel], sample.u(), sample.v()) - sample.color()[channel]) > 12) return List.of();
            }
        }
        if (hasColouredMarks(x, y, width, height, plane)) return List.of();
        // Small solid rectangles use existing Word-compatible VML, without extra image parts.
        // At most eight pieces approximate the local gradient; a white page still uses one.
        double dx = Arrays.stream(plane).mapToDouble(coefficients -> Math.abs(coefficients[1])).max().orElse(0);
        double dy = Arrays.stream(plane).mapToDouble(coefficients -> Math.abs(coefficients[2])).max().orElse(0);
        int columns = Math.max(1, Math.min(8, (int) Math.ceil(dx / 6)));
        int rows = Math.max(1, Math.min(8, (int) Math.ceil(dy / 6)));
        while (columns * rows > 8) {
            if (columns > 1 && (rows == 1 || dx / columns < dy / rows)) columns--;
            else rows--;
        }
        List<Fill> fills = new ArrayList<>();
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            double u = (column + .5) / columns - .5, v = (row + .5) / rows - .5;
            String color = "%02X%02X%02X".formatted(channel(plane[0], u, v), channel(plane[1], u, v), channel(plane[2], u, v));
            double x1 = mask.x() + mask.width() * column / columns;
            double x2 = mask.x() + mask.width() * (column + 1) / columns;
            double y1 = mask.y() + mask.height() * row / rows;
            double y2 = mask.y() + mask.height() * (row + 1) / rows;
            fills.add(new Fill(new Rect(x1, y1, x2 - x1, y2 - y1), color));
        }
        return List.copyOf(fills);
    }

    /** Inspect every pixel, never sparse samples, before reserving transparent edit space. */
    boolean uniformLightPaper(Rect region, String paperHex) {
        int rgb = Integer.parseInt(paperHex, 16);
        int[] paper = {(rgb >>> 16) & 255, (rgb >>> 8) & 255, rgb & 255};
        if (Arrays.stream(paper).min().orElse(0) < 180 || range(paper) > 8) return false;
        int left = (int) Math.floor((region.x() - background.x()) * sx);
        int top = (int) Math.floor((region.y() - background.y()) * sy);
        int right = (int) Math.ceil((region.right() - background.x()) * sx);
        int bottom = (int) Math.ceil((region.bottom() - background.y()) * sy);
        long pixels = (long) (right - left) * (bottom - top);
        if (left < 0 || top < 0 || right > image.getWidth() || bottom > image.getHeight()
                || pixels <= 0 || pixels > blankPixelBudget) return false;
        blankPixelBudget -= (int) pixels;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int[] sample = color(x, y);
            for (int channel = 0; channel < 3; channel++) {
                if (Math.abs(sample[channel] - paper[channel]) > 4) return false;
            }
        }
        return true;
    }

    private boolean hasColouredMarks(double x, double y, double width, double height, double[][] plane) {
        int columns = Math.max(1, Math.min(64, (int) Math.ceil(width)));
        int rows = Math.max(1, Math.min(64, (int) Math.ceil(height)));
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            int px = (int) Math.floor(x + width * (column + .5) / columns);
            int py = (int) Math.floor(y + height * (row + .5) / rows);
            if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) continue;
            int[] color = color(px, py);
            double u = (px + .5 - x) / width - .5, v = (py + .5 - y) / height - .5;
            int[] paper = {channel(plane[0], u, v), channel(plane[1], u, v), channel(plane[2], u, v)};
            if (range(paper) <= 24 && range(color) <= 24) continue; // Includes light ink on black paper.
            if (range(paper) <= 24 && brightness(paper) >= 180 && scanFringe(color, px, py)) continue;
            double length = 0, product = 0;
            for (int index = 0; index < 3; index++) { length += paper[index] * paper[index]; product += paper[index] * color[index]; }
            // Neutral ink and antialiased ink on tinted paper scale the paper channels together.
            double scale = length < 1 ? 0 : product / length;
            for (int index = 0; index < 3; index++) {
                if (Math.abs(color[index] - paper[index] * scale) > 24) return true;
            }
        }
        return false;
    }

    /** Scanner/JPEG blue and complementary yellow fringes must touch black ink.
     * Large coloured areas and saturated annotations cannot pass this local check. */
    private boolean scanFringe(int[] sample, int px, int py) {
        boolean blue = brightness(sample) < 245 && range(sample) <= 140
                && sample[0] <= sample[1] + 25 && sample[0] <= sample[2] + 15 && sample[1] <= sample[2] + 15;
        boolean yellow = Arrays.stream(sample).min().orElse(0) >= 120 && range(sample) <= 80
                && Math.abs(sample[0] - sample[1]) <= 20 && sample[2] <= Math.min(sample[0], sample[1]);
        if (!blue && !yellow) return false;
        for (int y = Math.max(0, py - 5); y <= Math.min(image.getHeight() - 1, py + 5); y++)
            for (int x = Math.max(0, px - 5); x <= Math.min(image.getWidth() - 1, px + 5); x++) {
                int[] neighbor = color(x, y);
                if (Math.max(neighbor[0], Math.max(neighbor[1], neighbor[2])) <= 110
                        && range(neighbor) <= 110 && neighbor[0] <= neighbor[1] + 25
                        && neighbor[1] <= neighbor[2] + 15) return true;
            }
        return false;
    }

    private static int range(int[] color) {
        return Math.max(color[0], Math.max(color[1], color[2])) - Math.min(color[0], Math.min(color[1], color[2]));
    }

    private static boolean paperChroma(int[] color, int[] median) {
        return Math.abs((color[0] - color[1]) - (median[0] - median[1])) <= 24
                && Math.abs((color[1] - color[2]) - (median[1] - median[2])) <= 24;
    }

    /** A text cover cannot erase a thin source rule extending beyond the text bounds.
     * Inspect a local band only; dark paper itself does not establish a thin rule. */
    private boolean crossesSourceRule(double x, double y, double width, double height) {
        int left = Math.max(0, (int) Math.floor(x)), top = Math.max(0, (int) Math.floor(y));
        int right = Math.min(image.getWidth(), (int) Math.ceil(x + width));
        int bottom = Math.min(image.getHeight(), (int) Math.ceil(y + height));
        int horizontal = Math.max(32, (int) Math.ceil(Math.max(6 * sx, width + 3)));
        int vertical = Math.max(32, (int) Math.ceil(Math.max(6 * sy, height + 3)));
        long checks = (long) (bottom - top) * (Math.min(image.getWidth(), right + horizontal) - Math.max(0, left - horizontal))
                + (long) (right - left) * (Math.min(image.getHeight(), bottom + vertical) - Math.max(0, top - vertical));
        // Oversized or numerous boxes fall back to the original scan instead of
        // allowing this optional refinement to dominate conversion time.
        if (checks <= 0 || checks > 2_000_000L || checks > rulePixelBudget) return true;
        rulePixelBudget -= checks;
        for (int row = top; row < bottom; row++) {
            int run = 0, gaps = 0;
            for (int column = Math.max(0, left - horizontal); column < Math.min(image.getWidth(), right + horizontal); column++) {
                // Text crossing a line can obscure its light side margins.
                // Require thinness outside the mask, then follow dark ink inside.
                boolean ink = column >= left && column < right ? dark(column, row) : thinDark(column, row, true);
                if (ink) { run++; gaps = 0; }
                else if (run > 0 && ++gaps <= 2) run++;
                else { run = 0; gaps = 0; }
                if (run >= horizontal && column >= left && column - run + 1 < right) return true;
            }
        }
        for (int column = left; column < right; column++) {
            int run = 0, gaps = 0;
            for (int row = Math.max(0, top - vertical); row < Math.min(image.getHeight(), bottom + vertical); row++) {
                boolean ink = row >= top && row < bottom ? dark(column, row) : thinDark(column, row, false);
                if (ink) { run++; gaps = 0; }
                else if (run > 0 && ++gaps <= 2) run++;
                else { run = 0; gaps = 0; }
                if (run >= vertical && row >= top && row - run + 1 < bottom) return true;
            }
        }
        return false;
    }

    private boolean thinDark(int x, int y, boolean horizontal) {
        if (!dark(x, y)) return false;
        int radius = Math.max(3, (int) Math.ceil((horizontal ? sy : sx) * .35));
        return horizontal ? light(x, y - radius) && light(x, y + radius)
                : light(x - radius, y) && light(x + radius, y);
    }

    private boolean dark(int x, int y) {
        int rgb = image.getRGB(x, y);
        return ((rgb >> 16) & 255) < 100 && ((rgb >> 8) & 255) < 100 && (rgb & 255) < 140;
    }

    private boolean light(int x, int y) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) return false;
        int rgb = image.getRGB(x, y);
        return ((rgb >> 16) & 255) >= 110 && ((rgb >> 8) & 255) >= 110 && (rgb & 255) >= 110;
    }

    private void addSample(List<Sample> samples, int px, int py, double x, double y, double width, double height) {
        if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) return;
        samples.add(new Sample((px + .5 - x) / width - .5, (py + .5 - y) / height - .5, color(px, py)));
    }

    private int[] color(int x, int y) {
        int rgba = image.getRGB(x, y), alpha = (rgba >>> 24) & 255;
        int[] color = {(rgba >>> 16) & 255, (rgba >>> 8) & 255, rgba & 255};
        for (int channel = 0; channel < 3; channel++) color[channel] = (color[channel] * alpha + 255 * (255 - alpha) + 127) / 255;
        return color;
    }

    private static double brightness(int[] color) {
        return color[0] * .299 + color[1] * .587 + color[2] * .114;
    }

    private static double[][] fit(List<Sample> samples) {
        double[][] matrix = new double[3][6];
        for (Sample sample : samples) {
            double[] terms = {1, sample.u(), sample.v()};
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) matrix[row][column] += terms[row] * terms[column];
                for (int channel = 0; channel < 3; channel++) matrix[row][channel + 3] += terms[row] * sample.color()[channel];
            }
        }
        for (int column = 0; column < 3; column++) {
            int pivot = column;
            for (int row = column + 1; row < 3; row++) if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) pivot = row;
            if (Math.abs(matrix[pivot][column]) < 1e-8) return null;
            double[] swap = matrix[pivot]; matrix[pivot] = matrix[column]; matrix[column] = swap;
            double divisor = matrix[column][column];
            for (int term = column; term < 6; term++) matrix[column][term] /= divisor;
            for (int row = 0; row < 3; row++) {
                if (row == column) continue;
                double multiplier = matrix[row][column];
                for (int term = column; term < 6; term++) matrix[row][term] -= multiplier * matrix[column][term];
            }
        }
        double[][] result = new double[3][3];
        for (int channel = 0; channel < 3; channel++) for (int term = 0; term < 3; term++) result[channel][term] = matrix[term][channel + 3];
        return result;
    }

    private static double value(double[] plane, double u, double v) { return plane[0] + plane[1] * u + plane[2] * v; }
    private static int channel(double[] plane, double u, double v) { return (int) Math.round(Math.max(0, Math.min(255, value(plane, u, v)))); }

    @Override public void close() { image.flush(); }
    record Fill(Rect box, String color) { }
    private record Sample(double u, double v, int[] color) { }
}
