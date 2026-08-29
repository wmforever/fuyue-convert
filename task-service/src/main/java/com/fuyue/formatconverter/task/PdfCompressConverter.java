package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PdfCompressConverter implements FileConverter {
    private static final long MAX_DECODED_IMAGE_PIXELS = 40_000_000L;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_COMPRESSED,
            "支持无损、均衡和强力三级 PDF 优化；结果不变小时自动保留原文件。",
            QualityLevel.BETA, ConversionStrategy.FIDELITY, List.of(),
            List.of("均衡和强力模式会重采样或重新编码图片；带数字签名的 PDF 会被严格拒绝"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        long sourceBytes = Files.size(input.path());
        PdfCompressionMode mode = input.options().compressionMode();
        int optimizedImages = 0;
        progress.update(TaskStage.PARSING, 20);
        try (PDDocument document = Loader.loadPDF(input.path().toFile())) {
            requireUnsigned(document);
            if (mode != PdfCompressionMode.LOSSLESS) {
                optimizedImages = optimizeImages(document, mode,
                        (done, total) -> progress.update(TaskStage.RENDERING,
                                25 + Math.min(50, done * 50 / Math.max(1, total))));
            }
            progress.update(TaskStage.RENDERING, 80);
            document.save(outputPath.toFile(), CompressParameters.DEFAULT_COMPRESSION);
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 压缩");
        int actualPages = ConversionGuards.requirePdfPageCount(outputPath, limits);
        if (actualPages != pageCount) throw new IOException("压缩 PDF 页数不一致");

        long optimizedBytes = Files.size(outputPath);
        List<ConversionWarning> warnings;
        if (optimizedBytes >= sourceBytes) {
            Files.copy(input.path(), outputPath, StandardCopyOption.REPLACE_EXISTING);
            warnings = List.of(ConversionWarning.of(WarningCode.PDF_SIZE_NOT_REDUCED,
                    "源 PDF 已较为紧凑，本次优化未减小体积，已保留原文件（" + formatBytes(sourceBytes) + "）。", null));
        } else {
            double saved = (sourceBytes - optimizedBytes) * 100d / sourceBytes;
            String imageNote = optimizedImages > 0 ? "，处理 " + optimizedImages + " 张图片" : "";
            warnings = List.of(ConversionWarning.of(WarningCode.PDF_COMPRESSION_APPLIED,
                    "PDF 已按" + modeLabel(mode) + "完成优化" + imageNote + "：" + formatBytes(sourceBytes)
                            + " → " + formatBytes(optimizedBytes) + "，节省 " + String.format("%.1f", saved) + "% 。", null));
        }
        progress.update(TaskStage.RENDERING, 90);
        return new ConversionOutput(outputPath,
                input.displayName().replaceFirst("(?i)\\.pdf$", "-optimized.pdf"), pageCount, warnings);
    }

    private int optimizeImages(PDDocument document, PdfCompressionMode mode, ImageProgress progress) throws IOException {
        int total = countImages(document);
        ImageOptimizer optimizer = new ImageOptimizer(document, mode, progress, total);
        for (var page : document.getPages()) optimizer.optimize(page.getResources());
        return optimizer.optimized;
    }

    private int countImages(PDDocument document) throws IOException {
        Set<COSDictionary> resources = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSDictionary> images = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var page : document.getPages()) collectImages(page.getResources(), resources, images);
        return images.size();
    }

    private void collectImages(PDResources resources, Set<COSDictionary> seenResources,
                               Set<COSDictionary> images) throws IOException {
        if (resources == null || !seenResources.add(resources.getCOSObject())) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) images.add(image.getCOSObject());
            else if (object instanceof PDFormXObject form) collectImages(form.getResources(), seenResources, images);
        }
    }

    private void requireUnsigned(PDDocument document) throws IOException {
        if (!document.getSignatureDictionaries().isEmpty()) {
            throw new ConversionFailureException("PDF_SIGNATURE_PRESENT",
                    "PDF 包含数字签名；压缩重写会使签名失效，已拒绝处理");
        }
    }

    private String modeLabel(PdfCompressionMode mode) {
        return switch (mode) {
            case LOSSLESS -> "无损模式";
            case BALANCED -> "均衡模式";
            case STRONG -> "强力模式";
        };
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024d);
        return String.format("%.1f MB", bytes / 1024d / 1024d);
    }

    private final class ImageOptimizer {
        private final PDDocument document;
        private final PdfCompressionMode mode;
        private final ImageProgress progress;
        private final int total;
        private final Set<COSDictionary> seenResources = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<COSDictionary, PDImageXObject> replacements = new IdentityHashMap<>();
        private int processed;
        private int optimized;

        private ImageOptimizer(PDDocument document, PdfCompressionMode mode, ImageProgress progress, int total) {
            this.document = document;
            this.mode = mode;
            this.progress = progress;
            this.total = total;
        }

        private void optimize(PDResources resources) throws IOException {
            if (resources == null || !seenResources.add(resources.getCOSObject())) return;
            for (COSName name : resources.getXObjectNames()) {
                PDXObject object = resources.getXObject(name);
                if (object instanceof PDImageXObject image) {
                    PDImageXObject replacement = replacements.get(image.getCOSObject());
                    if (replacement == null && !replacements.containsKey(image.getCOSObject())) {
                        replacement = optimizeImage(image);
                        replacements.put(image.getCOSObject(), replacement);
                        processed++;
                        progress.update(processed, total);
                    }
                    if (replacement != null) resources.put(name, replacement);
                } else if (object instanceof PDFormXObject form) {
                    optimize(form.getResources());
                }
            }
        }

        private PDImageXObject optimizeImage(PDImageXObject image) throws IOException {
            if (image.isStencil() || image.getWidth() < 64 || image.getHeight() < 64) return null;
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels > MAX_DECODED_IMAGE_PIXELS) {
                throw new ConversionFailureException("PDF_IMAGE_LIMIT_EXCEEDED",
                        "PDF 内嵌图片像素超过压缩安全上限");
            }
            BufferedImage source = image.getImage();
            int maxDimension = mode == PdfCompressionMode.STRONG ? 1200 : 1800;
            BufferedImage scaled = scale(source, maxDimension);
            PDImageXObject replacement;
            if (scaled.getColorModel().hasAlpha()) {
                replacement = LosslessFactory.createFromImage(document, scaled);
            } else {
                float quality = mode == PdfCompressionMode.STRONG ? 0.68f : 0.82f;
                replacement = JPEGFactory.createFromImage(document, toRgb(scaled), quality, 144);
            }
            optimized++;
            return replacement;
        }
    }

    private BufferedImage scale(BufferedImage source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        double ratio = Math.min(1d, maxDimension / (double) Math.max(width, height));
        if (ratio >= 1d) return source;
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) return source;
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try { graphics.drawImage(source, 0, 0, Color.WHITE, null); }
        finally { graphics.dispose(); }
        return rgb;
    }

    @FunctionalInterface
    private interface ImageProgress { void update(int completed, int total); }
}
