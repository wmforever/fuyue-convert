package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ScannedContentDetector;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class OfdOcrSupport {
    private final TesseractOcrConverter ocr;
    private final String unavailableCode;
    private final String unavailableMessage;

    OfdOcrSupport(TesseractOcrConverter.Settings settings) {
        this.ocr = new TesseractOcrConverter(DocumentFormat.PNG, settings);
        this.unavailableCode = null;
        this.unavailableMessage = null;
    }

    OfdOcrSupport(TesseractOcrConverter.Capability capability) {
        this.ocr = capability.available() ? new TesseractOcrConverter(DocumentFormat.PNG, capability.settings()) : null;
        this.unavailableCode = capability.errorCode();
        this.unavailableMessage = capability.message();
    }

    DocumentModel recognizeRequiredPages(DocumentModel parsed, Path workDir, ParseLimits limits,
                                         ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        requireCompletePageModel(parsed);
        List<PageModel> pages = new ArrayList<>(parsed.pages().size());
        for (PageModel page : parsed.pages()) {
            if (!requiresOcr(page)) {
                pages.add(page);
                continue;
            }
            List<ImageBlock> requiredImages;
            try {
                requiredImages = ScannedContentDetector.imagesRequiringOcr(
                        page.textBlocks(), page.images(), page.physicalBox());
            } catch (ScannedContentDetector.AnalysisLimitException e) {
                throw new ConversionFailureException("OCR_IMAGE_LIMIT_EXCEEDED",
                        "OFD 第 " + page.pageNumber() + " 页图片候选过多，拒绝不完整转换。");
            }
            requireAvailable(page.pageNumber());
            boolean strictRegions = !requiredImages.isEmpty();
            List<ImageBlock> candidates = strictRegions
                    ? requiredImages : ScannedContentDetector.contentImages(page.images());
            List<TextBlock> recognized = new ArrayList<>();
            List<TextBlock> allTexts = new ArrayList<>(page.textBlocks());
            List<ImageBlock> ocrSources = new ArrayList<>();
            List<ConversionWarning> imageWarnings = new ArrayList<>();
            double confidenceTotal = 0d;
            int wordCount = 0;
            int imageIndex = 0;
            for (ImageBlock image : candidates) {
                int currentImage = ++imageIndex;
                try {
                    byte[] data = image.data();
                    if (data.length == 0) {
                        if (strictRegions) throw new ConversionFailureException("OCR_IMAGE_INVALID",
                                "OFD 第 " + page.pageNumber() + " 页包含无法读取的必需 OCR 图像区域");
                        continue;
                    }
                    ocr.requireImageWithinOcrLimit(data);
                    var decoded = ImageIO.read(new ByteArrayInputStream(data));
                    if (decoded == null) {
                        if (strictRegions) throw new ConversionFailureException("OCR_IMAGE_INVALID",
                                "OFD 第 " + page.pageNumber() + " 页包含无法解码的必需 OCR 图像区域");
                        continue;
                    }
                    Path pageWork = Files.createDirectories(workDir.resolve("page-%04d".formatted(page.pageNumber())));
                    Path raster = pageWork.resolve("image-%04d.png".formatted(currentImage));
                    if (!ImageIO.write(decoded, "png", raster.toFile())) {
                        if (strictRegions) throw new ConversionFailureException("OCR_IMAGE_INVALID",
                                "OFD 第 " + page.pageNumber() + " 页必需 OCR 图像无法标准化");
                        continue;
                    }
                    progress.update(TaskStage.RECOGNIZING,
                            30 + (int) (page.pageNumber() * 35d / Math.max(1, parsed.sourcePageCount())));
                    TesseractOcrConverter.RecognitionResult result = ocr.recognizeLayoutResult(raster,
                            pageWork.resolve("ocr-" + currentImage), page.pageNumber(), image.box(), limits);
                    ocrSources.add(image);
                    if (strictRegions) {
                        ocr.requireUsableResult(result,
                                "OFD 第 " + page.pageNumber() + " 页图片 " + currentImage);
                    }
                    List<TextBlock> beyondNative = result.blocks().stream()
                            .filter(block -> !OcrTextDeduplicator.duplicates(block, page.textBlocks()))
                            .toList();
                    if (strictRegions && beyondNative.isEmpty()) {
                        throw new ConversionFailureException("OCR_NO_NEW_TEXT",
                                "OFD 第 " + page.pageNumber() + " 页图片 " + currentImage
                                        + " 缺少原生文字层，但 OCR 未补充出新文字");
                    }
                    for (TextBlock block : beyondNative) {
                        if (OcrTextDeduplicator.duplicates(block, allTexts)) continue;
                        TextBlock addition = new TextBlock(
                                block.id() + "-i" + currentImage, block.pageNumber(), block.box(),
                                block.text(), block.baselineY(), block.style(), recognized.size() + 1,
                                block.textOffsetXmm(), block.textOffsetYmm(), block.advancesMm(), block.transform(),
                                block.ocrWords());
                        recognized.add(addition);
                        allTexts.add(addition);
                    }
                    confidenceTotal += result.confidence() * result.wordCount();
                    wordCount += result.wordCount();
                    // Keep candidate/coverage/conflict semantics at image scope. The page result below
                    // is only a weighted summary; combining flags would misdescribe other scans.
                    imageWarnings.addAll(ocr.warningsFor(result, page.pageNumber(),
                                    "OFD 第 " + page.pageNumber() + " 页图片 " + currentImage).stream()
                            .filter(warning -> warning.code() != WarningCode.OCR_APPLIED).toList());
                } catch (ConversionFailureException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ConversionFailureException("OCR_IMAGE_FAILED",
                            "OFD 第 " + page.pageNumber() + " 页图片 " + currentImage + " OCR 处理失败");
                }
            }
            TesseractOcrConverter.RecognitionResult pageResult = new TesseractOcrConverter.RecognitionResult(
                    recognized, wordCount == 0 ? 0d : confidenceTotal / wordCount, wordCount);
            ocr.requireUsableResult(pageResult, "OFD 第 " + page.pageNumber() + " 页");
            List<ConversionWarning> warnings = page.warnings().stream()
                    .filter(warning -> warning.code() != WarningCode.OCR_REQUIRED)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            warnings.addAll(ocr.warningsFor(pageResult, page.pageNumber(),
                    "OFD 第 " + page.pageNumber() + " 页"));
            warnings.addAll(imageWarnings);
            pages.add(new PageModel(page.pageNumber(), page.physicalBox(), allTexts, page.lines(),
                    withOcrBackgroundRole(page.images(), ocrSources),
                    List.of(), List.of(), warnings));
        }
        return new DocumentModel(parsed.sourceName(), parsed.parserName() + (ocr == null ? "" : " + Tesseract"),
                parsed.sourcePageCount(), pages, parsed.warnings());
    }

    private void requireAvailable(int pageNumber) throws ConversionFailureException {
        if (ocr == null) {
            throw new ConversionFailureException(unavailableCode == null ? "OCR_ENGINE_UNAVAILABLE" : unavailableCode,
                    "OFD 第 " + pageNumber + " 页需要 OCR；" +
                            (unavailableMessage == null ? "本地 OCR 引擎不可用" : unavailableMessage));
        }
    }

    private void requireCompletePageModel(DocumentModel parsed) throws ConversionFailureException {
        if (parsed.pages().size() != parsed.sourcePageCount()) {
            throw new ConversionFailureException("OCR_PAGE_MISSING", "OFD 页面模型不完整，拒绝静默漏页");
        }
        for (int index = 0; index < parsed.pages().size(); index++) {
            if (parsed.pages().get(index).pageNumber() != index + 1) {
                throw new ConversionFailureException("OCR_PAGE_MISSING", "OFD 页面编号不连续，拒绝静默漏页");
            }
        }
    }

    private boolean requiresOcr(PageModel page) {
        return page.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_REQUIRED);
    }

    private List<ImageBlock> withOcrBackgroundRole(List<ImageBlock> images, List<ImageBlock> ocrSources) {
        java.util.Set<ImageBlock> sources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        sources.addAll(ocrSources);
        return images.stream().map(image -> sources.contains(image)
                ? new ImageBlock(image.id(), image.pageNumber(), image.box(), image.mimeType(), image.data(),
                "OCR_SCAN_BACKGROUND", image.zOrder())
                : image).toList();
    }

}
