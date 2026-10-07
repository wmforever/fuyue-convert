package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.Rect;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/** Optional, bounded horizontal-print deskew; source pixels are never modified. */
final class OcrDeskew {
    private OcrDeskew() { }

    static Prepared prepare(BufferedImage source, long maxPixels, long deadline) {
        double angle = detect(source, deadline);
        if (angle == 0 || System.nanoTime() >= deadline) return null;
        double radians = Math.toRadians(angle), cos = Math.cos(radians), sin = Math.sin(radians);
        int width = (int) Math.ceil(source.getWidth() * cos + source.getHeight() * Math.abs(sin));
        int height = (int) Math.ceil(source.getHeight() * cos + source.getWidth() * Math.abs(sin));
        if (width > 32768 || height > 32768 || (long) width * height > maxPixels) return null;
        AffineTransform forward = new AffineTransform();
        forward.translate(width / 2d, height / 2d);
        forward.rotate(-radians);
        forward.translate(-source.getWidth() / 2d, -source.getHeight() / 2d);
        try {
            BufferedImage corrected = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = corrected.createGraphics();
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, forward, null); graphics.dispose();
            if (System.nanoTime() >= deadline) { corrected.flush(); return null; }
            return new Prepared(corrected, angle, forward.createInverse());
        } catch (java.awt.geom.NoninvertibleTransformException impossible) {
            return null;
        }
    }

    static double detect(BufferedImage source, long deadline) {
        if (source.getWidth() < 64 || source.getHeight() < 64 || source.getWidth() > 32768 || source.getHeight() > 32768
                || System.nanoTime() >= deadline) return 0;
        double scale = Math.min(1d, 700d / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = thumbnail.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null); graphics.dispose();
        try {
            int[] xs = new int[100_000], ys = new int[100_000];
            int count = 0, paper = 0;
            for (int y = 0; y < height; y++) {
                if (System.nanoTime() >= deadline) return 0;
                for (int x = 0; x < width; x++) {
                    int gray = thumbnail.getRaster().getSample(x, y, 0);
                    if (gray > 200) paper++;
                    if (gray < 110) {
                        if (count == xs.length) return 0;
                        xs[count] = x; ys[count++] = y;
                    }
                }
            }
            // Dark/photographic pages and sparse dirt provide no trustworthy horizontal-print evidence.
            if (count < 200 || paper < width * height * .70 || count > width * height * .20) return 0;
            double[] scores = new double[65];
            // The +width offset also needs room for positive projection of x
            // at negative angles, including ink along the bottom-right edge.
            int[] rows = new int[2 * width + height + 4];
            int best = 32;
            for (int index = 0; index < scores.length; index++) {
                if (System.nanoTime() >= deadline) return 0;
                java.util.Arrays.fill(rows, 0);
                double theta = Math.toRadians(-8 + index * .25), cos = Math.cos(theta), sin = Math.sin(theta);
                for (int point = 0; point < count; point++) {
                    int row = (int) Math.round(ys[point] * cos - xs[point] * sin) + width;
                    rows[row]++;
                }
                for (int value : rows) scores[index] += (double) value * value;
                if (scores[index] > scores[best]) best = index;
            }
            double angle = -8 + best * .25;
            double alternative = 0;
            for (int index = 0; index < scores.length; index++) {
                if (Math.abs(index - best) >= 4) alternative = Math.max(alternative, scores[index]);
            }
            if (Math.abs(angle) < 1 || Math.abs(angle) >= 7.75 || scores[best] < scores[32] * 1.40
                    || scores[best] < alternative * 1.04) return 0;
            return angle;
        } finally { thumbnail.flush(); }
    }

    record Prepared(BufferedImage image, double degrees, AffineTransform inverse) implements AutoCloseable {
        Point2D original(double x, double y) { return inverse.transform(new Point2D.Double(x, y), null); }
        Rect originalBounds(Rect corrected, double sx, double sy, Rect physical) {
            double left = Double.POSITIVE_INFINITY, top = left, right = Double.NEGATIVE_INFINITY, bottom = right;
            for (double x : new double[]{corrected.x(), corrected.right()}) for (double y : new double[]{corrected.y(), corrected.bottom()}) {
                Point2D point = original(x / sx, y / sy);
                left = Math.min(left, point.getX()); right = Math.max(right, point.getX());
                top = Math.min(top, point.getY()); bottom = Math.max(bottom, point.getY());
            }
            return new Rect(physical.x() + left * sx, physical.y() + top * sy, (right - left) * sx, (bottom - top) * sy);
        }
        @Override public void close() { image.flush(); }
    }
}
