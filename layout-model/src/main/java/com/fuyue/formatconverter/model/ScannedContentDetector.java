package com.fuyue.formatconverter.model;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Conservative detector for document-like raster regions that are not backed by
 * enough native text. It is shared by PDF and OFD parsers so editable/extraction
 * routes cannot silently treat a small header as the whole page contents.
 */
public final class ScannedContentDetector {
    private static final double LARGE_IMAGE_PAGE_COVERAGE = 0.45d;
    private static final double MINIMUM_TILE_PAGE_COVERAGE = 0.08d;
    private static final int MINIMUM_TEXT_LAYER_CHARACTERS = 80;
    private static final int VERTICAL_BAND_COUNT = 4;
    private static final int MINIMUM_OCCUPIED_VERTICAL_BANDS = 3;
    private static final double MINIMUM_TEXT_VERTICAL_SPAN = 0.45d;
    private static final int MAX_SAMPLE_AXIS = 384;
    private static final double MINIMUM_PAPER_RATIO = 0.50d;
    private static final double MINIMUM_NEUTRAL_INK_RATIO = 0.0008d;
    private static final int MINIMUM_TEXT_LIKE_BANDS = 2;
    private static final int MINIMUM_ROW_TRANSITIONS = 8;
    private static final int MAX_RASTER_CANDIDATES = 64;
    private static final int COVERAGE_GRID_SIZE = 64;

    private ScannedContentDetector() { }

    public static List<ImageBlock> imagesRequiringOcr(List<TextBlock> texts,
                                                       List<ImageBlock> images,
                                                       Rect pageBox) {
        if (images == null || images.isEmpty()) return List.of();
        double pageArea = Math.max(1d, pageBox.width() * pageBox.height());
        List<TextBlock> safeTexts = texts == null ? List.of() : texts;
        List<ImageBlock> geometryCandidates = new ArrayList<>(MAX_RASTER_CANDIDATES);
        for (ImageBlock image : images) {
            if (!isContentImage(image)
                    || image.box().intersectionArea(pageBox) / pageArea < MINIMUM_TILE_PAGE_COVERAGE) {
                continue;
            }
            if (geometryCandidates.size() == MAX_RASTER_CANDIDATES) {
                throw new AnalysisLimitException(
                        "扫描内容候选图片超过安全分析上限 " + MAX_RASTER_CANDIDATES);
            }
            geometryCandidates.add(image);
        }
        List<ImageBlock> insufficient = geometryCandidates.stream()
                .filter(image -> !hasSufficientNativeTextLayer(image.box(), safeTexts))
                .filter(ScannedContentDetector::looksLikeScannedDocument)
                .toList();
        if (insufficient.isEmpty()) return List.of();
        boolean hasLargeImage = insufficient.stream()
                .anyMatch(image -> image.box().intersectionArea(pageBox) / pageArea
                        >= LARGE_IMAGE_PAGE_COVERAGE);
        double unionCoverage = clippedUnionArea(insufficient.stream().map(ImageBlock::box).toList(), pageBox)
                / pageArea;
        return hasLargeImage || unionCoverage >= LARGE_IMAGE_PAGE_COVERAGE ? insufficient : List.of();
    }

    public static boolean requiresOcr(List<TextBlock> texts, List<ImageBlock> images, Rect pageBox) {
        List<ImageBlock> contentImages = contentImages(images);
        if (contentImages.isEmpty()) return false;
        List<ImageBlock> requiredImages = imagesRequiringOcr(texts, contentImages, pageBox);
        return nativeCharacterCount(texts) == 0 || !requiredImages.isEmpty();
    }

    public static List<ImageBlock> contentImages(List<ImageBlock> images) {
        if (images == null || images.isEmpty()) return List.of();
        return images.stream().filter(ScannedContentDetector::isContentImage).toList();
    }

    public static boolean looksLikeScannedDocument(ImageBlock image) {
        if (!isContentImage(image)) return false;
        byte[] data = image.data();
        if (data.length == 0) return true;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) return true;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return true;
            ImageReader reader = readers.next();
            BufferedImage raster;
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1) return true;
                int xSubsampling = Math.max(1, (int) Math.ceil(width / (double) MAX_SAMPLE_AXIS));
                int ySubsampling = Math.max(1, (int) Math.ceil(height / (double) MAX_SAMPLE_AXIS));
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(xSubsampling, ySubsampling, 0, 0);
                raster = reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
            if (raster == null || raster.getWidth() < 1 || raster.getHeight() < 1) return true;
            long samples = 0;
            long paper = 0;
            long neutralInk = 0;
            boolean[] textLikeRows = new boolean[raster.getHeight()];
            for (int y = 0; y < raster.getHeight(); y++) {
                int rowInk = 0;
                int transitions = 0;
                boolean previousInk = false;
                for (int x = 0; x < raster.getWidth(); x++) {
                    int argb = raster.getRGB(x, y);
                    if (((argb >>> 24) & 0xff) < 32) continue;
                    int red = (argb >>> 16) & 0xff;
                    int green = (argb >>> 8) & 0xff;
                    int blue = argb & 0xff;
                    int maximum = Math.max(red, Math.max(green, blue));
                    int minimum = Math.min(red, Math.min(green, blue));
                    double luminance = red * 0.2126d + green * 0.7152d + blue * 0.0722d;
                    samples++;
                    if (luminance >= 210d) paper++;
                    boolean ink = luminance <= 205d && maximum - minimum <= 55;
                    if (ink) {
                        neutralInk++;
                        rowInk++;
                    }
                    if (x > 0 && ink != previousInk) transitions++;
                    previousInk = ink;
                }
                textLikeRows[y] = rowInk >= Math.max(2, raster.getWidth() / 200)
                        && transitions >= MINIMUM_ROW_TRANSITIONS;
            }
            if (samples == 0) return false;
            return paper / (double) samples >= MINIMUM_PAPER_RATIO
                    && neutralInk / (double) samples >= MINIMUM_NEUTRAL_INK_RATIO
                    && horizontalBandCount(textLikeRows) >= MINIMUM_TEXT_LIKE_BANDS;
        } catch (Exception ignored) {
            // An unreadable large document image cannot safely be discarded by an
            // editable/text route. Let the OCR path return a stable decode error.
            return true;
        }
    }

    private static boolean isContentImage(ImageBlock image) {
        return image != null && !"SIGNATURE".equalsIgnoreCase(image.role());
    }

    private static int nativeCharacterCount(List<TextBlock> texts) {
        if (texts == null) return 0;
        return texts.stream().map(TextBlock::text).mapToInt(ScannedContentDetector::characterCount).sum();
    }

    private static boolean hasSufficientNativeTextLayer(Rect image, List<TextBlock> texts) {
        if (image.height() <= 0d) return false;
        int characters = 0;
        boolean[] verticalBands = new boolean[VERTICAL_BAND_COUNT];
        double minimumCenterY = Double.POSITIVE_INFINITY;
        double maximumCenterY = Double.NEGATIVE_INFINITY;
        for (TextBlock text : texts) {
            if (!overlapsMostly(image, text.box())) continue;
            characters += characterCount(text.text());
            double centerY = text.box().center().y();
            minimumCenterY = Math.min(minimumCenterY, centerY);
            maximumCenterY = Math.max(maximumCenterY, centerY);
            double relative = Math.max(0d, Math.min(0.999999d,
                    (centerY - image.y()) / image.height()));
            verticalBands[(int) (relative * VERTICAL_BAND_COUNT)] = true;
        }
        if (characters < MINIMUM_TEXT_LAYER_CHARACTERS) return false;
        int occupiedBands = 0;
        for (boolean occupied : verticalBands) if (occupied) occupiedBands++;
        double verticalSpan = (maximumCenterY - minimumCenterY) / image.height();
        return occupiedBands >= MINIMUM_OCCUPIED_VERTICAL_BANDS
                && verticalSpan >= MINIMUM_TEXT_VERTICAL_SPAN;
    }

    private static boolean overlapsMostly(Rect image, Rect text) {
        double textArea = Math.max(0.01d, text.width() * text.height());
        return image.intersectionArea(text) / textArea >= 0.5d
                || image.contains(text.center(), 0.5d);
    }

    private static int characterCount(String value) {
        if (value == null || value.isEmpty()) return 0;
        return (int) value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
    }

    private static int horizontalBandCount(boolean[] rows) {
        int gapTolerance = Math.max(2, rows.length / 100);
        int bands = 0;
        int lastOccupied = Integer.MIN_VALUE / 2;
        for (int row = 0; row < rows.length; row++) {
            if (!rows[row]) continue;
            if (row - lastOccupied > gapTolerance) bands++;
            lastOccupied = row;
        }
        return bands;
    }

    private static double clippedUnionArea(List<Rect> rectangles, Rect clip) {
        if (clip.width() <= 0d || clip.height() <= 0d || rectangles.isEmpty()) return 0d;
        boolean[] occupied = new boolean[COVERAGE_GRID_SIZE * COVERAGE_GRID_SIZE];
        int occupiedCount = 0;
        for (Rect rectangle : rectangles) {
            Rect clipped = intersection(rectangle, clip);
            if (clipped.width() <= 0d || clipped.height() <= 0d) continue;
            int left = gridFloor((clipped.x() - clip.x()) / clip.width());
            int right = gridCeiling((clipped.right() - clip.x()) / clip.width());
            int top = gridFloor((clipped.y() - clip.y()) / clip.height());
            int bottom = gridCeiling((clipped.bottom() - clip.y()) / clip.height());
            for (int row = top; row < bottom; row++) {
                for (int column = left; column < right; column++) {
                    int index = row * COVERAGE_GRID_SIZE + column;
                    if (!occupied[index]) {
                        occupied[index] = true;
                        occupiedCount++;
                    }
                }
            }
        }
        return clip.width() * clip.height() * occupiedCount
                / (double) (COVERAGE_GRID_SIZE * COVERAGE_GRID_SIZE);
    }

    private static Rect intersection(Rect first, Rect second) {
        double left = Math.max(first.x(), second.x());
        double top = Math.max(first.y(), second.y());
        double right = Math.min(first.right(), second.right());
        double bottom = Math.min(first.bottom(), second.bottom());
        return new Rect(left, top, Math.max(0d, right - left), Math.max(0d, bottom - top));
    }

    private static int gridFloor(double fraction) {
        return Math.max(0, Math.min(COVERAGE_GRID_SIZE - 1,
                (int) Math.floor(fraction * COVERAGE_GRID_SIZE)));
    }

    private static int gridCeiling(double fraction) {
        return Math.max(1, Math.min(COVERAGE_GRID_SIZE,
                (int) Math.ceil(fraction * COVERAGE_GRID_SIZE)));
    }

    public static final class AnalysisLimitException extends RuntimeException {
        public AnalysisLimitException(String message) { super(message); }
    }
}
