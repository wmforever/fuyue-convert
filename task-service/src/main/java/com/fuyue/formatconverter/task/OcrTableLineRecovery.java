package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.awt.image.BufferedImage;
import java.util.*;

/** Local retry for abnormal OCR boxes over proven thin table rules. Source pixels stay untouched. */
final class OcrTableLineRecovery {
    record Region(int x, int y, int width, int height, Rect box, BufferedImage cleaned) { }
    private record Band(int first, int last) { }
    private OcrTableLineRecovery() { }

    static List<Region> prepare(BufferedImage image, Rect physical,
            TesseractOcrConverter.RecognitionResult original, long deadline) {
        if (original.wordCount() > 1400 || physical.width() <= 0 || physical.height() <= 0) return List.of();
        var words = original.blocks().stream().flatMap(b -> b.ocrWords().stream()).toList();
        double[] heights = words.stream().filter(w -> w.confidence() >= .85
                && w.text().codePoints().anyMatch(Character::isLetterOrDigit))
                .mapToDouble(w -> w.box().height()).sorted().toArray();
        if (heights.length < 10) return List.of();
        double typical = heights[heights.length / 2];
        List<Rect> seeds = new ArrayList<>();
        for (var block : original.blocks()) {
            Rect seed = null;
            for (var word : block.ocrWords()) if (word.confidence() < .6
                    && (word.box().height() > typical * 1.5
                    || word.box().width() > Math.max(1, word.text().codePointCount(0, word.text().length())) * word.box().height() * 3)) {
                seed = seed == null ? word.box() : seed.union(word.box());
            }
            if (seed != null && seed.width() > physical.width() * .4) seeds.add(seed);
        }
        // Several spanning OCR lines may describe the same small table.
        for (int i = 0; i < seeds.size(); i++) for (int j = i + 1; j < seeds.size();) {
            Rect a = seeds.get(i), b = seeds.get(j);
            if (a.intersectionArea(b) > 0) { seeds.set(i, a.union(b)); seeds.remove(j); i = -1; break; }
            j++;
        }
        List<Region> result = new ArrayList<>();
        for (Rect seed : seeds) {
            if (System.nanoTime() >= deadline || result.size() == 2) break;
            int x = Math.max(0, (int) Math.floor((seed.x() - physical.x() - typical * 2) * image.getWidth() / physical.width()));
            int y = Math.max(0, (int) Math.floor((seed.y() - physical.y() - typical * 2) * image.getHeight() / physical.height()));
            int right = Math.min(image.getWidth(), (int) Math.ceil((seed.right() - physical.x() + typical * 2) * image.getWidth() / physical.width()));
            int bottom = Math.min(image.getHeight(), (int) Math.ceil((seed.bottom() - physical.y() + typical * 2) * image.getHeight() / physical.height()));
            // A spanning prediction may stop at an inner column. Include the central page width
            // so that the true outside rules, rather than an incomplete column subset, are tested.
            x = Math.min(x, image.getWidth() / 10);
            right = Math.max(right, image.getWidth() * 9 / 10);
            Region region = detect(image, physical, x, y, right - x, bottom - y,
                    typical * image.getHeight() / physical.height(), deadline);
            if (region != null) result.add(region);
        }
        return List.copyOf(result);
    }

    private static Region detect(BufferedImage image, Rect physical, int ox, int oy, int width, int height,
            double typical, long deadline) {
        if (width < 300 || height < 60 || (long) width * height > 4_000_000 || System.nanoTime() >= deadline) return null;
        int[] rgb = image.getRGB(ox, oy, width, height, null, 0, width);
        boolean[] ink = new boolean[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            if ((i & 16383) == 0 && System.nanoTime() >= deadline) return null;
            int r = rgb[i] >> 16 & 255, g = rgb[i] >> 8 & 255, b = rgb[i] & 255;
            if (rgb[i] >>> 24 != 255) return null;
            // A stamp/signature cannot be interpreted as a neutral table rule.
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 100
                    && (r > g + 30 || g > b + 30)) return null;
            ink[i] = Math.max(r, Math.max(g, b)) < 150;
        }
        int[] rows = new int[height];
        for (int y = 0; y < height; y++) {
            if (System.nanoTime() >= deadline) return null;
            int run = 0;
            for (int x = 0; x < width; x++) {
                boolean hit = false;
                for (int dy = -2; dy <= 2 && !hit; dy++) if (y + dy >= 0 && y + dy < height) hit = ink[(y + dy) * width + x];
                run = hit ? run + 1 : 0; rows[y] = Math.max(rows[y], run);
            }
        }
        List<Band> ys = bands(rows, width * .65);
        if (ys.size() < 2 || ys.size() > 17) return null;
        int top = ys.get(0).first(), bottom = ys.get(ys.size() - 1).last();
        int[] columns = new int[width];
        for (int x = 0; x < width; x++) {
            if (System.nanoTime() >= deadline) return null;
            int run = 0;
            for (int y = top; y <= bottom; y++) {
                boolean hit = false;
                for (int dx = -2; dx <= 2 && !hit; dx++) if (x + dx >= 0 && x + dx < width) hit = ink[y * width + x + dx];
                run = hit ? run + 1 : 0; columns[x] = Math.max(columns[x], run);
            }
        }
        List<Band> xs = bands(columns, (bottom - top + 1) * .9);
        if (xs.size() < 3 || xs.size() > 9) return null;
        int left = xs.get(0).first(), right = xs.get(xs.size() - 1).last();
        // Long runs elsewhere in a row do not prove this rectangle's edges.
        int ruleLeft = (xs.get(0).first() + xs.get(0).last()) / 2;
        int ruleRight = (xs.get(xs.size() - 1).first() + xs.get(xs.size() - 1).last()) / 2;
        int ruleTop = (ys.get(0).first() + ys.get(0).last()) / 2;
        int ruleBottom = (ys.get(ys.size() - 1).first() + ys.get(ys.size() - 1).last()) / 2;
        for (Band band : ys) {
            int gaps = 0;
            for (int x = ruleLeft; x <= ruleRight; x++) {
            if (System.nanoTime() >= deadline) return null;
            boolean hit = false;
            for (int y = band.first(); y <= band.last() && !hit; y++) hit = ink[y * width + x];
            if (!hit && ++gaps > 1) return null;
            }
        }
        for (Band band : xs) {
            int gaps = 0;
            for (int y = ruleTop; y <= ruleBottom; y++) {
            if (System.nanoTime() >= deadline) return null;
            boolean hit = false;
            for (int x = band.first(); x <= band.last() && !hit; x++) hit = ink[y * width + x];
            if (!hit && ++gaps > 1) return null;
            }
        }
        for (Band band : ys) if (band.last() - band.first() + 1 > Math.max(8, typical * .3)) return null;
        for (Band band : xs) if (band.last() - band.first() + 1 > Math.max(8, typical * .3)) return null;
        // Require a crossing at every grid intersection, including the outside rectangle.
        for (Band y : ys) for (Band x : xs) {
            boolean hit = false;
            for (int yy = y.first(); yy <= y.last() && !hit; yy++)
                for (int xx = x.first(); xx <= x.last() && !hit; xx++) hit = ink[yy * width + xx];
            if (!hit) return null;
        }
        int cx = left, cy = top, cw = right - left + 1, ch = bottom - top + 1;
        BufferedImage cleaned = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        cleaned.setRGB(0, 0, cw, ch, image.getRGB(ox + cx, oy + cy, cw, ch, null, 0, cw), 0, cw);
        // Only established thin rule bands are cleared, on an OCR-only copy.
        var g = cleaned.createGraphics(); g.setColor(java.awt.Color.WHITE);
        for (Band y : ys) g.fillRect(0, y.first() - cy, cw, y.last() - y.first() + 1);
        for (Band x : xs) g.fillRect(x.first() - cx, 0, x.last() - x.first() + 1, ch);
        g.dispose();
        // Gray table headers can otherwise binarize as one solid bar in Tesseract.
        binarize(cleaned);
        Rect box = new Rect(physical.x() + (ox + cx) * physical.width() / image.getWidth(),
                physical.y() + (oy + cy) * physical.height() / image.getHeight(),
                cw * physical.width() / image.getWidth(), ch * physical.height() / image.getHeight());
        return new Region(ox + cx, oy + cy, cw, ch, box, cleaned);
    }

    private static List<Band> bands(int[] values, double threshold) {
        List<Band> result = new ArrayList<>();
        for (int i = 0; i < values.length; i++) if (values[i] >= threshold) {
            int start = i;
            while (i + 1 < values.length && values[i + 1] >= threshold) i++;
            result.add(new Band(start, i));
        }
        return result;
    }

    /** Global Otsu is bounded to this proven, neutral table crop, never the full scan. */
    private static void binarize(BufferedImage image) {
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        int[] histogram = new int[256]; long sum = 0;
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int gray = ((rgb >> 16 & 255) * 299 + (rgb >> 8 & 255) * 587 + (rgb & 255) * 114 + 500) / 1000;
            pixels[i] = gray; histogram[gray]++; sum += gray;
        }
        long lowerSum = 0; int lowerCount = 0, threshold = 0; double maximum = -1;
        for (int value = 0; value < 256; value++) {
            lowerCount += histogram[value]; lowerSum += (long) value * histogram[value];
            int upperCount = pixels.length - lowerCount;
            if (lowerCount == 0 || upperCount == 0) continue;
            double difference = lowerSum / (double) lowerCount - (sum - lowerSum) / (double) upperCount;
            double variance = (double) lowerCount * upperCount * difference * difference;
            if (variance > maximum) { maximum = variance; threshold = value; }
        }
        // White cleared rules must not form a second "paper" class that makes
        // the entire gray cell foreground. Bound the threshold below local paper.
        int count = 0, paper = 255;
        for (int value = 0; value < 256; value++) {
            count += histogram[value];
            if (count >= pixels.length * .6) { paper = value; break; }
        }
        threshold = Math.min(threshold, paper * 3 / 4);
        for (int i = 0; i < pixels.length; i++) pixels[i] = pixels[i] <= threshold ? 0xFF000000 : 0xFFFFFFFF;
        image.setRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
    }

    static TesseractOcrConverter.RecognitionResult select(Region region,
            TesseractOcrConverter.RecognitionResult source, TesseractOcrConverter.RecognitionResult candidate) {
        var old = source.blocks().stream().flatMap(b -> b.ocrWords().stream())
                .filter(w -> w.box().intersectionArea(region.box()) > 0).toList();
        var next = candidate.blocks().stream().flatMap(b -> b.ocrWords().stream()).toList();
        double oldConfidence = old.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(1);
        if (old.isEmpty() || next.isEmpty() || next.size() > 250 || candidate.confidence() < .75
                || candidate.confidence() < oldConfidence + .10 || source.wordCount() - old.size() + next.size() > 1400) return source;
        // Keep literal number tokens (signs, units and leading zeros included). New numeric fields need review.
        List<String> available = new ArrayList<>(next.stream().map(TextBlock.OcrWord::text).toList());
        String joined = String.join("", available).replaceAll("\\s+", "");
        for (var word : old) {
            if (word.text().codePoints().anyMatch(Character::isDigit)) {
                if (!available.remove(word.text())) return source;
            } else if (word.confidence() >= .85 && !joined.contains(word.text().replaceAll("\\s+", ""))) return source;
        }
        if (next.stream().anyMatch(w -> !region.box().contains(w.box().center(), .001))) return source;
        List<TextBlock> blocks = new ArrayList<>(); boolean inserted = false;
        for (var block : source.blocks()) {
            var retained = block.ocrWords().stream().filter(w -> w.box().intersectionArea(region.box()) == 0).toList();
            if (retained.size() == block.ocrWords().size()) { blocks.add(block); continue; }
            // Reject a line straddling real source content. No guessing its order or geometry.
            if (!retained.isEmpty()) return source;
            if (!inserted) {
                for (var b : candidate.blocks()) blocks.add(new TextBlock("ocr-table-p" + b.pageNumber() + "-" + region.y() + "-" + blocks.size(),
                        b.pageNumber(), b.box(), b.text(), b.baselineY(), b.style(), blocks.size() + 1,
                        b.textOffsetXmm(), b.textOffsetYmm(), b.advancesMm(), b.transform(), b.ocrWords()));
                inserted = true;
            }
        }
        if (!inserted) return source;
        List<TextBlock> ordered = new ArrayList<>();
        for (var b : blocks) ordered.add(new TextBlock(b.id(), b.pageNumber(), b.box(), b.text(), b.baselineY(), b.style(),
                ordered.size() + 1, b.textOffsetXmm(), b.textOffsetYmm(), b.advancesMm(), b.transform(), b.ocrWords()));
        var all = ordered.stream().flatMap(b -> b.ocrWords().stream()).toList();
        String superseded = String.join(" | ", old.stream().map(TextBlock.OcrWord::text).limit(12).toList());
        if (superseded.length() > 160) superseded = superseded.substring(0, 160);
        List<String> conflicts = new ArrayList<>(source.conflicts());
        conflicts.add("表格异常区域在识别副本中去除连续细框线并归一化灰底后重新识别；已有数字字面值已核对。原区域候选记录："
                + superseded + "。原扫描保留，新增字段和识别错误仍需对照原图复核。");
        return new TesseractOcrConverter.RecognitionResult(ordered,
                all.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0), all.size(),
                source.imageEnhanced(), 0, conflicts, true, false, source.ruledGridRecovery(), true);
    }
}
