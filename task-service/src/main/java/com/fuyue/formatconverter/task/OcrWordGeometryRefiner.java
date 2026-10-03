package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Refines regular full-width Chinese lines only when every character has an ink-separated cell. */
final class OcrWordGeometryRefiner {
    private OcrWordGeometryRefiner() { }

    static TextBlock refine(TextBlock line, BufferedImage image, Rect page) {
        int[] characters = line.text().codePoints().toArray();
        if (characters.length < 6 || line.ocrWords().isEmpty() || !han(characters[0])) return line;
        for (int character : characters) if (!han(character)
                && !(character >= 0x3000 && character <= 0x303f)
                && !(character >= 0xff01 && character <= 0xff60)) return line;
        if (line.ocrWords().stream().mapToInt(word -> word.text().codePointCount(0, word.text().length())).sum()
                != characters.length) return line;

        double sx = image.getWidth() / page.width(), sy = image.getHeight() / page.height();
        int left = Math.max(0, (int) Math.floor((line.box().x() - page.x()) * sx));
        int right = Math.min(image.getWidth(), (int) Math.ceil((line.box().right() - page.x()) * sx));
        int top = Math.max(0, (int) Math.floor((line.box().y() - page.y()) * sy));
        int bottom = Math.min(image.getHeight(), (int) Math.ceil((line.box().bottom() - page.y()) * sy));
        if (right <= left || bottom <= top) return line;
        int[] ink = bounds(image, left, top, right, bottom);
        if (ink == null) return line;
        double pitch;
        if (han(characters[characters.length - 1])) {
            pitch = (ink[2] - ink[0]) / (characters.length - 0.1d);
        } else {
            double[] heights = line.ocrWords().stream().filter(word -> word.text().codePoints().anyMatch(OcrWordGeometryRefiner::han))
                    .mapToDouble(word -> word.box().height() * sy).sorted().toArray();
            if (heights.length == 0) return line;
            pitch = heights[heights.length / 2] / 0.9d;
            double span = (ink[2] - ink[0]) / pitch;
            if (span < characters.length - 1.1d || span > characters.length + 0.1d) return line;
        }
        if (pitch < 8 || (ink[3] - ink[1]) / pitch < 0.55d || (ink[3] - ink[1]) / pitch > 1.3d) return line;
        double origin = ink[0] - pitch * 0.05d;
        int[] boundaries = new int[characters.length + 1];
        boundaries[0] = left; boundaries[characters.length] = right;
        for (int index = 1; index < characters.length; index++) {
            int ideal = (int) Math.round(origin + index * pitch);
            int boundary = -1;
            for (int distance = 0; distance <= Math.ceil(pitch * 0.18d) && boundary < 0; distance++) {
                for (int candidate : new int[]{ideal - distance, ideal + distance}) {
                    if (candidate > boundaries[index - 1] && candidate > left && candidate < right - 1
                            && blankColumn(image, candidate - 1, top, bottom)
                            && blankColumn(image, candidate, top, bottom)
                            && blankColumn(image, candidate + 1, top, bottom)) { boundary = candidate; break; }
                }
            }
            if (boundary < 0) return line;
            boundaries[index] = boundary;
        }
        List<Rect> cells = new ArrayList<>();
        for (int index = 0; index < characters.length; index++) {
            int[] box = bounds(image, boundaries[index], top, boundaries[index + 1], bottom);
            if (box == null || box[2] - box[0] > pitch * 1.05d
                    || han(characters[index]) && box[2] - box[0] < pitch * 0.25d) return line;
            cells.add(new Rect(page.x() + box[0] / sx, page.y() + box[1] / sy,
                    (box[2] - box[0]) / sx, (box[3] - box[1]) / sy));
        }
        List<TextBlock.OcrWord> words = new ArrayList<>();
        int index = 0;
        for (TextBlock.OcrWord word : line.ocrWords()) {
            int count = word.text().codePointCount(0, word.text().length());
            Rect box = cells.get(index++);
            for (int part = 1; part < count; part++) box = box.union(cells.get(index++));
            words.add(new TextBlock.OcrWord(box, word.text(), word.confidence()));
        }
        Rect box = words.stream().map(TextBlock.OcrWord::box).reduce(Rect::union).orElseThrow();
        return new TextBlock(line.id(), line.pageNumber(), box, line.text(), line.baselineY(), line.style(),
                line.zOrder(), line.textOffsetXmm(), line.textOffsetYmm(), line.advancesMm(), line.transform(), words);
    }

    private static boolean han(int value) { return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN; }

    private static boolean blankColumn(BufferedImage image, int x, int top, int bottom) {
        for (int y = top; y < bottom; y++) if (ink(image.getRGB(x, y))) return false;
        return true;
    }

    private static int[] bounds(BufferedImage image, int left, int top, int right, int bottom) {
        int minX = right, minY = bottom, maxX = left, maxY = top;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            if (ink(image.getRGB(x, y))) {
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + 1); maxY = Math.max(maxY, y + 1);
            }
        }
        return maxX > minX && maxY > minY ? new int[]{minX, minY, maxX, maxY} : null;
    }

    private static boolean ink(int rgb) {
        double alpha = ((rgb >>> 24) & 255) / 255d;
        double luminance = ((rgb >>> 16) & 255) * 0.299 + ((rgb >>> 8) & 255) * 0.587 + (rgb & 255) * 0.114;
        return luminance * alpha + 255d * (1d - alpha) < 170;
    }
}
