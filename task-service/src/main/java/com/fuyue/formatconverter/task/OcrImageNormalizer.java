package com.fuyue.formatconverter.task;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Applies EXIF orientation before OCR so coordinates match the visible page. */
final class OcrImageNormalizer {
    private OcrImageNormalizer() { }

    static Prepared prepare(Path source, DocumentFormat format, Path workDir) throws IOException {
        ImageMetadataReader.ImageMetadata metadata = ImageMetadataReader.read(source, format);
        if (metadata.orientation() == 1) {
            int[] size = dimensions(source);
            return new Prepared(source, size[0], size[1], metadata, false);
        }
        BufferedImage original = ImageIO.read(source.toFile());
        if (original == null) throw new IOException("无法解码 OCR 图片");
        BufferedImage oriented = orient(original, metadata.orientation());
        int destinationWidth = oriented.getWidth();
        int destinationHeight = oriented.getHeight();
        Files.createDirectories(workDir);
        Path normalized = workDir.resolve("ocr-oriented.png");
        if (!ImageIO.write(oriented, "png", normalized.toFile())) throw new IOException("无法写入方向校正后的 OCR 图片");
        return new Prepared(normalized, destinationWidth, destinationHeight, metadata, true);
    }

    private static int[] dimensions(Path source) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) throw new IOException("无法读取 OCR 图片尺寸");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("无法识别 OCR 图片格式");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    static BufferedImage orient(BufferedImage original, int orientation) {
        if (orientation <= 1 || orientation > 8) return original;
        int width = original.getWidth();
        int height = original.getHeight();
        boolean swapsAxes = orientation >= 5;
        int destinationWidth = swapsAxes ? height : width;
        int destinationHeight = swapsAxes ? width : height;
        BufferedImage oriented = new BufferedImage(destinationWidth, destinationHeight,
                original.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = width - 1 - x; dy = y; }
                    case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                    case 4 -> { dx = x; dy = height - 1 - y; }
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = height - 1 - y; dy = x; }
                    case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                    case 8 -> { dx = y; dy = width - 1 - x; }
                    default -> { dx = x; dy = y; }
                }
                oriented.setRGB(dx, dy, original.getRGB(x, y));
            }
        }
        return oriented;
    }

    record Prepared(Path path, int width, int height, ImageMetadataReader.ImageMetadata metadata,
                    boolean orientationApplied) { }
}
