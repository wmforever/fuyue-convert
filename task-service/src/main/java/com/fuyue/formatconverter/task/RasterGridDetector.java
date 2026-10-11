package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.LineElement;
import com.fuyue.formatconverter.model.Point;
import com.fuyue.formatconverter.model.Rect;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Recovers the thin black/gray rules of a scanned table as layout lines.
 *
 * The detector is deliberately conservative: it only returns a grid when
 * several long vertical rules share one continuous y range. Text strokes and
 * watermarks cannot satisfy the per-cell coverage checks used below.
 */
final class RasterGridDetector {
    private static final int MAX_PIXELS = 80_000_000;
    private static final double DARK = 155d;
    private static final int BAND_GAP = 1;

    private RasterGridDetector() { }

    static List<LineElement> detect(BufferedImage image, Rect physical, int pageNumber, long deadline) {
        if (image == null || physical == null || physical.width() <= 0 || physical.height() <= 0
                || (long) image.getWidth() * image.getHeight() > MAX_PIXELS
                || System.nanoTime() >= deadline) return List.of();
        int width = image.getWidth(), height = image.getHeight();
        boolean[] dark = new boolean[width * height];
        int[] rowCount = new int[height], columnCount = new int[width];
        for (int y = 0; y < height; y++) {
            if ((y & 127) == 0 && System.nanoTime() >= deadline) return List.of();
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
                int luminance = (299 * r + 587 * g + 114 * b + 500) / 1000;
                boolean value = luminance <= DARK;
                dark[y * width + x] = value;
                if (value) { rowCount[y]++; columnCount[x]++; }
            }
        }

        List<Band> horizontalBands = bands(rowCount, width * .55d);
        if (horizontalBands.size() < 3) return List.of();
        List<Band> verticalCandidates = bands(columnCount, height * .18d);
        // Page borders are useful only after the table's y range is known.
        verticalCandidates = verticalCandidates.stream()
                .filter(b -> b.center() > 2 && b.center() < width - 3)
                .filter(b -> b.length() <= Math.max(12, width / 30))
                // Repeated glyph strokes can contribute enough dark pixels to
                // pass a global projection. A real rule has one long run.
                .filter(b -> maxDarkRun(dark, width, height, b) >= height * .25d)
                .toList();
        if (verticalCandidates.size() < 3) return List.of();

        int[] active = new int[height];
        for (Band x : verticalCandidates) {
            for (int y = 0; y < height; y++) {
                if (nearDark(dark, width, height, x.first(), x.last(), y, y)) active[y]++;
            }
        }
        List<Band> activeRanges = bands(active, Math.max(2, Math.floor(verticalCandidates.size() * .25d))).stream()
                .filter(range -> range.length() >= height * .05d).toList();
        if (activeRanges.isEmpty()) return List.of();
        // A repeated header can leave a short blank separator between two
        // adjacent portions of the same scanned table. Keep the complete span
        // so the first explanatory grid is not discarded in favour of the
        // longer price grid below it.
        Band tableRange = new Band(activeRanges.stream().mapToInt(Band::first).min().orElseThrow(),
                activeRanges.stream().mapToInt(Band::last).max().orElseThrow());

        List<Band> horizontal = horizontalBands.stream()
                .filter(b -> b.center() >= tableRange.first() - 3 && b.center() <= tableRange.last() + 3)
                .toList();
        if (horizontal.size() < 2) return List.of();
        List<Band> vertical = new ArrayList<>(verticalCandidates);
        vertical.add(new Band(0, 0));
        vertical.add(new Band(width - 1, width - 1));
        vertical.sort(Comparator.comparingInt(Band::first));
        vertical = deduplicate(vertical);
        if (vertical.size() < 4) return List.of();

        List<LineElement> result = new ArrayList<>();
        int lineId = 0;
        // Add only horizontal rule segments that are actually inked between
        // neighboring vertical rules. Missing segments preserve merged cells.
        for (Band y : horizontal) {
            for (int x = 0; x + 1 < vertical.size(); x++) {
                Band left = vertical.get(x), right = vertical.get(x + 1);
                if (right.center() - left.center() < 8) continue;
                if (coverageHorizontal(dark, width, height, y, left.center(), right.center()) < .58) continue;
                result.add(horizontalLine(++lineId, pageNumber, physical, width, height, y.center(), left.center(), right.center()));
            }
        }
        // Vertical rules are checked separately in every row band. This is what
        // keeps the large merged model/name cells merged in the exported sheet.
        for (Band x : vertical) {
            for (int y = 0; y + 1 < horizontal.size(); y++) {
                Band top = horizontal.get(y), bottom = horizontal.get(y + 1);
                if (bottom.center() - top.center() < 8) continue;
                if (coverageVertical(dark, width, height, x, top.center(), bottom.center()) < .58) continue;
                result.add(verticalLine(++lineId, pageNumber, physical, width, height, x.center(), top.center(), bottom.center()));
            }
        }
        return result.size() >= 8 ? List.copyOf(result) : List.of();
    }

    private static LineElement horizontalLine(int id, int pageNumber, Rect p, int w, int h, int y, int x1, int x2) {
        return new LineElement("ocr-raster-h-" + id, pageNumber,
                point(p, w, h, x1, y), point(p, w, h, x2, y), .2, ColorValue.BLACK, Integer.MIN_VALUE);
    }

    private static LineElement verticalLine(int id, int pageNumber, Rect p, int w, int h, int x, int y1, int y2) {
        return new LineElement("ocr-raster-v-" + id, pageNumber,
                point(p, w, h, x, y1), point(p, w, h, x, y2), .2, ColorValue.BLACK, Integer.MIN_VALUE);
    }

    private static Point point(Rect p, int w, int h, int x, int y) {
        return new Point(p.x() + (double) x * p.width() / w,
                p.y() + (double) y * p.height() / h);
    }

    private static double coverageHorizontal(boolean[] dark, int w, int h, Band y, int left, int right) {
        int hits = 0, total = Math.max(1, right - left + 1);
        for (int x = left; x <= right; x++) if (nearDark(dark, w, h, x, x, y.first(), y.last())) hits++;
        return hits / (double) total;
    }

    private static double coverageVertical(boolean[] dark, int w, int h, Band x, int top, int bottom) {
        int hits = 0, total = Math.max(1, bottom - top + 1);
        for (int y = top; y <= bottom; y++) if (nearDark(dark, w, h, x.first(), x.last(), y, y)) hits++;
        return hits / (double) total;
    }

    private static boolean nearDark(boolean[] dark, int width, int height,
                                    int x1, int x2, int y1, int y2) {
        int left = Math.max(0, x1 - 1), right = Math.min(width - 1, x2 + 1);
        int top = Math.max(0, y1 - 1), bottom = Math.min(height - 1, y2 + 1);
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++)
            if (dark[y * width + x]) return true;
        return false;
    }

    private static int maxDarkRun(boolean[] dark, int width, int height, Band band) {
        int longest = 0, run = 0;
        for (int y = 0; y < height; y++) {
            if (nearDark(dark, width, height, band.first(), band.last(), y, y)) run++;
            else { longest = Math.max(longest, run); run = 0; }
        }
        return Math.max(longest, run);
    }

    private static List<Band> bands(int[] values, double threshold) {
        List<Band> result = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] < threshold) continue;
            int first = i, last = i, gap = 0;
            while (last + 1 < values.length) {
                if (values[last + 1] >= threshold) { last++; gap = 0; }
                else if (++gap <= BAND_GAP) last++;
                else break;
            }
            result.add(new Band(first, last));
            i = last;
        }
        return result;
    }

    private static List<Band> deduplicate(List<Band> source) {
        List<Band> result = new ArrayList<>();
        for (Band next : source) {
            if (!result.isEmpty() && next.first() - result.get(result.size() - 1).last() <= 3) {
                Band previous = result.remove(result.size() - 1);
                result.add(new Band(previous.first(), Math.max(previous.last(), next.last())));
            } else result.add(next);
        }
        return result;
    }

    private record Band(int first, int last) {
        int center() { return (first + last) / 2; }
        int length() { return last - first + 1; }
    }
}
