package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ScannedContentDetector;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class PdfOcrSupport {
    private static final float OCR_DPI = 300f;
    private static final int EMBEDDED_IMAGE_OCR_MAX_EDGE = 1600;
    private final TesseractOcrConverter ocr;
    private final String unavailableCode;
    private final String unavailableMessage;

    PdfOcrSupport(TesseractOcrConverter.Settings settings) {
        this.ocr = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        this.unavailableCode = null;
        this.unavailableMessage = null;
    }

    PdfOcrSupport(TesseractOcrConverter.Capability capability) {
        this.ocr = capability.available() ? new TesseractOcrConverter(DocumentFormat.PNG, capability.settings()) : null;
        this.unavailableCode = capability.errorCode();
        this.unavailableMessage = capability.message();
    }

    DocumentModel recognizeMissingPages(Path source, DocumentModel parsed, Path workDir,
                                        ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        requireCompletePageModel(parsed);
        List<PageModel> pages = new ArrayList<>(parsed.pages().size());
        List<ConversionWarning> documentWarnings = new ArrayList<>(parsed.warnings());
        try (var pdf = Loader.loadPDF(source.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int index = 0; index < parsed.pages().size(); index++) {
                PageModel page = parsed.pages().get(index);
                boolean visibleContent = hasVisibleContent(pdf.getPage(index));
                List<ImageBlock> requiredImages;
                try {
                    requiredImages = ScannedContentDetector.imagesRequiringOcr(
                            page.textBlocks(), page.images(), page.physicalBox());
                } catch (ScannedContentDetector.AnalysisLimitException e) {
                    throw new ConversionFailureException("OCR_IMAGE_LIMIT_EXCEEDED",
                            "PDF 第 " + page.pageNumber() + " 页图片候选过多，拒绝不完整转换。");
                }
                if (!visibleContent) {
                    pages.add(page);
                    continue;
                }
                if (!page.textBlocks().isEmpty() && requiredImages.isEmpty()) {
                    pages.add(page);
                    continue;
                }
                requireAvailable(page.pageNumber());
                if (!page.textBlocks().isEmpty()) {
                    pages.add(recognizeRequiredImages(page, requiredImages, workDir, limits));
                    continue;
                }
                var pdfPage = pdf.getPage(index);
                var crop = pdfPage.getCropBox();
                double unit = pdfPage.getUserUnit();
                if (!Double.isFinite(unit) || unit <= 0) unit = 1d;
                ConversionGuards.requireRenderBounds(crop.getWidth() * unit, crop.getHeight() * unit,
                        OCR_DPI, limits);
                ocr.requireRenderedPageWithinOcrLimit(page.physicalBox(), OCR_DPI);
                progress.update(TaskStage.RECOGNIZING,
                        30 + (int) ((index + 1) * 35d / Math.max(1, parsed.pages().size())));
                Path image = workDir.resolve("pdf-ocr-page-%04d.png".formatted(page.pageNumber()));
                if (!ImageIO.write(renderer.renderImageWithDPI(index, OCR_DPI, ImageType.RGB),
                        "png", image.toFile())) {
                    throw new java.io.IOException("无法写入 PDF OCR 页面图片");
                }
                ConversionGuards.requireTotalSize(List.of(image), limits, "PDF OCR 页面图片");
                TesseractOcrConverter.RecognitionResult recognized = ocr.recognizeLayoutResult(
                        image, workDir.resolve("page-%04d".formatted(page.pageNumber())), page.pageNumber(),
                        page.physicalBox(), limits);
                ocr.requireUsableResult(recognized, "PDF 第 " + page.pageNumber() + " 页");
                List<ConversionWarning> warnings = withoutOcrRequired(page.warnings());
                warnings.addAll(ocr.warningsFor(recognized, page.pageNumber(),
                        "PDF 第 " + page.pageNumber() + " 页"));
                ImageBlock renderedPage = new ImageBlock(
                        "pdf-p%d-rendered-background".formatted(page.pageNumber()),
                        page.pageNumber(), page.physicalBox(), "image/png", Files.readAllBytes(image),
                        "OCR_PAGE_BACKGROUND", Integer.MIN_VALUE);
                pages.add(new PageModel(page.pageNumber(), page.physicalBox(), recognized.blocks(), List.of(),
                        List.of(renderedPage), List.of(), List.of(), warnings));
            }
        }
        return new DocumentModel(parsed.sourceName(), parsed.parserName() + (ocr == null ? "" : " + Tesseract"),
                parsed.sourcePageCount(), pages, documentWarnings);
    }

    /** OCRs every image region that the parser classified as required; failure is fatal. */
    private PageModel recognizeRequiredImages(PageModel page, List<ImageBlock> requiredImages,
                                               Path workDir, ParseLimits limits) throws Exception {
        if (requiredImages.isEmpty()) return page;
        List<TextBlock> texts = new ArrayList<>(page.textBlocks());
        List<ConversionWarning> warnings = withoutOcrRequired(page.warnings());
        int imageIndex = 0;
        for (ImageBlock image : requiredImages) {
            byte[] data = image.data();
            if (data.length == 0 || image.box().width() < 4d || image.box().height() < 4d) {
                throw new ConversionFailureException("OCR_IMAGE_INVALID",
                        "PDF 第 " + page.pageNumber() + " 页包含无法读取的必需 OCR 图像区域");
            }
            Path source = workDir.resolve("pdf-image-ocr-%04d-%03d.png".formatted(page.pageNumber(), ++imageIndex));
            try {
                Files.write(source, data);
                Path prepared = OcrImageNormalizer.downscaleForOcr(source,
                        workDir.resolve("pdf-image-ocr-%04d-%03d-small.png".formatted(page.pageNumber(), imageIndex)),
                        EMBEDDED_IMAGE_OCR_MAX_EDGE);
                ocr.requireImageWithinOcrLimit(prepared);
                TesseractOcrConverter.RecognitionResult recognized = ocr.recognizeLayoutResult(prepared,
                        workDir.resolve("image-%04d-%03d".formatted(page.pageNumber(), imageIndex)),
                        page.pageNumber(), image.box(), limits);
                ocr.requireUsableResult(recognized,
                        "PDF 第 " + page.pageNumber() + " 页图片 " + imageIndex);
                List<TextBlock> beyondNative = recognized.blocks().stream()
                        .filter(block -> !OcrTextDeduplicator.duplicates(block, page.textBlocks())).toList();
                if (beyondNative.isEmpty()) {
                    throw new ConversionFailureException("OCR_NO_NEW_TEXT",
                            "PDF 第 " + page.pageNumber() + " 页图片 " + imageIndex
                                    + " 缺少原生文字层，但 OCR 未补充出新文字");
                }
                for (TextBlock block : beyondNative) {
                    if (!OcrTextDeduplicator.duplicates(block, texts)) texts.add(block);
                }
                warnings.addAll(ocr.warningsFor(recognized, page.pageNumber(),
                        "PDF 第 " + page.pageNumber() + " 页图片 " + imageIndex));
            } catch (ConversionFailureException e) {
                throw e;
            } catch (Exception e) {
                throw new ConversionFailureException("OCR_IMAGE_FAILED",
                        "PDF 第 " + page.pageNumber() + " 页图片 " + imageIndex + " OCR 处理失败");
            }
        }
        return new PageModel(page.pageNumber(), page.physicalBox(), texts, page.lines(),
                withOcrBackgroundRole(page.images(), requiredImages),
                List.of(), List.of(), warnings);
    }

    private List<ImageBlock> withOcrBackgroundRole(List<ImageBlock> images, List<ImageBlock> ocrSources) {
        java.util.Set<ImageBlock> sources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        sources.addAll(ocrSources);
        return images.stream().map(image -> sources.contains(image)
                ? new ImageBlock(image.id(), image.pageNumber(), image.box(), image.mimeType(), image.data(),
                "OCR_SCAN_BACKGROUND", image.zOrder())
                : image).toList();
    }

    private List<ConversionWarning> withoutOcrRequired(List<ConversionWarning> warnings) {
        return warnings.stream()
                .filter(warning -> warning.code() != WarningCode.OCR_REQUIRED)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void requireAvailable(int pageNumber) throws ConversionFailureException {
        if (ocr == null) {
            throw new ConversionFailureException(unavailableCode == null ? "OCR_ENGINE_UNAVAILABLE" : unavailableCode,
                    "PDF 第 " + pageNumber + " 页需要 OCR；" +
                            (unavailableMessage == null ? "本地 OCR 引擎不可用" : unavailableMessage));
        }
    }

    private void requireCompletePageModel(DocumentModel parsed) throws ConversionFailureException {
        if (parsed.pages().size() != parsed.sourcePageCount()) {
            throw new ConversionFailureException("OCR_PAGE_MISSING", "PDF 页面模型不完整，拒绝静默漏页");
        }
        for (int index = 0; index < parsed.pages().size(); index++) {
            if (parsed.pages().get(index).pageNumber() != index + 1) {
                throw new ConversionFailureException("OCR_PAGE_MISSING", "PDF 页面编号不连续，拒绝静默漏页");
            }
        }
    }

    private boolean hasVisibleContent(org.apache.pdfbox.pdmodel.PDPage page) throws Exception {
        if (!page.hasContents()) return !page.getAnnotations().isEmpty();
        try (InputStream input = page.getContents()) {
            int value;
            while ((value = input.read()) >= 0) {
                if (!Character.isWhitespace(value)) return true;
            }
        }
        return !page.getAnnotations().isEmpty();
    }
}
