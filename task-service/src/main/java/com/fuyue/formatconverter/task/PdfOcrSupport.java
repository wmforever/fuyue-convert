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
    private final RapidOcrLayoutEngine rapid;
    private final String unavailableCode;
    private final String unavailableMessage;

    PdfOcrSupport(TesseractOcrConverter.Settings settings) {
        this.ocr = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        this.rapid = RapidOcrLayoutEngine.configured(settings);
        this.unavailableCode = null;
        this.unavailableMessage = null;
    }

    PdfOcrSupport(TesseractOcrConverter.Capability capability) {
        this.ocr = capability.available() ? new TesseractOcrConverter(DocumentFormat.PNG, capability.settings()) : null;
        this.rapid = capability.available() ? RapidOcrLayoutEngine.configured(capability.settings()) : null;
        this.unavailableCode = capability.errorCode();
        this.unavailableMessage = capability.message();
    }

    DocumentModel recognizeMissingPages(Path source, DocumentModel parsed, Path workDir,
                                        ParseLimits limits, ConversionProgress progress) throws Exception {
        return recognizeMissingPages(source, parsed, workDir, limits, progress, false);
    }

    String engineName() { return rapid == null ? "Tesseract" : "RapidOCR"; }
    boolean usesRapid() { return rapid != null; }

    private TesseractOcrConverter.RecognitionResult recognize(Path image, Path workDir, int page,
            com.fuyue.formatconverter.model.Rect physical, ParseLimits limits) throws Exception {
        return rapid == null ? ocr.recognizeLayoutResult(image, workDir, page, physical, limits)
                : rapid.recognize(image, workDir, page, physical, limits);
    }

    private List<ConversionWarning> warningsFor(TesseractOcrConverter.RecognitionResult result, int page, String scope) {
        return rapid == null ? ocr.warningsFor(result, page, scope) : rapid.warnings(result, page, scope);
    }

    DocumentModel recognizeMissingPagesForText(Path source, DocumentModel parsed, Path workDir,
                                               ParseLimits limits, ConversionProgress progress) throws Exception {
        return recognizeMissingPages(source, parsed, workDir, limits, progress, true);
    }

    private DocumentModel recognizeMissingPages(Path source, DocumentModel parsed, Path workDir,
                                                ParseLimits limits, ConversionProgress progress,
                                                boolean respectVisibility) throws Exception {
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
                    PdfOcrVisibility visibility = null;
                    if (respectVisibility) {
                        try {
                            visibility = PdfOcrVisibility.inspect(pdf.getPage(index), page.images(), limits.maxEntries());
                        } catch (java.io.IOException e) {
                            throw new ConversionFailureException("OCR_VISIBILITY_UNCERTAIN",
                                    "PDF 扫描文字可见性分析超出限制或无法完成，拒绝合并旧图像文字。");
                        }
                    }
                    pages.add(recognizeRequiredImages(page, requiredImages, workDir, limits, visibility));
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
                TesseractOcrConverter.RecognitionResult recognized = recognize(
                        image, workDir.resolve("page-%04d".formatted(page.pageNumber())), page.pageNumber(),
                        page.physicalBox(), limits);
                ocr.requireUsableResult(recognized, "PDF 第 " + page.pageNumber() + " 页");
                List<ConversionWarning> warnings = withoutOcrRequired(page.warnings());
                warnings.addAll(warningsFor(recognized, page.pageNumber(),
                        "PDF 第 " + page.pageNumber() + " 页"));
                ImageBlock renderedPage = new ImageBlock(
                        "pdf-p%d-rendered-background".formatted(page.pageNumber()),
                        page.pageNumber(), page.physicalBox(), "image/png", Files.readAllBytes(image),
                        "OCR_PAGE_BACKGROUND", Integer.MIN_VALUE);
                pages.add(new PageModel(page.pageNumber(), page.physicalBox(), recognized.blocks(), List.of(),
                        List.of(renderedPage), List.of(), List.of(), warnings));
            }
        }
        return new DocumentModel(parsed.sourceName(), parsed.parserName() + (ocr == null ? "" : " + " + engineName()),
                parsed.sourcePageCount(), pages, documentWarnings);
    }

    /** OCRs every image region that the parser classified as required; failure is fatal. */
    private PageModel recognizeRequiredImages(PageModel page, List<ImageBlock> requiredImages,
                                               Path workDir, ParseLimits limits, PdfOcrVisibility visibility) throws Exception {
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
                TesseractOcrConverter.RecognitionResult recognized = recognize(prepared,
                        workDir.resolve("image-%04d-%03d".formatted(page.pageNumber(), imageIndex)),
                        page.pageNumber(), image.box(), limits);
                ocr.requireUsableResult(recognized,
                        "PDF 第 " + page.pageNumber() + " 页图片 " + imageIndex);
                var visible = visibility == null ? new PdfOcrVisibility.Filtered(recognized.blocks(), 0)
                        : visibility.filter(recognized.blocks());
                List<TextBlock> beyondNative = visible.blocks().stream()
                        .filter(block -> !OcrTextDeduplicator.duplicates(block, page.textBlocks())).toList();
                if (beyondNative.isEmpty() && !(visible.blocks().isEmpty() && visible.hiddenWords() > 0)) {
                    throw new ConversionFailureException("OCR_NO_NEW_TEXT",
                            "PDF 第 " + page.pageNumber() + " 页图片 " + imageIndex
                                    + " 缺少原生文字层，但 OCR 未补充出新文字");
                }
                for (TextBlock block : beyondNative) {
                    if (!OcrTextDeduplicator.duplicates(block, texts)) texts.add(block);
                }
                warnings.addAll(warningsFor(recognized, page.pageNumber(),
                        "PDF 第 " + page.pageNumber() + " 页图片 " + imageIndex));
                if (visible.hiddenWords() > 0) warnings.add(new ConversionWarning(
                        WarningCode.OCR_OCCLUDED_TEXT_IGNORED,
                        "PDF 第 " + page.pageNumber() + " 页有 " + visible.hiddenWords()
                                + " 个底图 OCR 词被后绘制的不透明遮罩完整覆盖，未并入可见文字；其余 OCR 仍需复核。",
                        page.pageNumber(), image.box(), null));
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
