package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.PageModel;
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
            requireAvailable(page.pageNumber());
            List<TextBlock> recognized = new ArrayList<>();
            double confidenceTotal = 0d;
            int wordCount = 0;
            int imageIndex = 0;
            for (ImageBlock image : page.images()) {
                if ("SIGNATURE".equalsIgnoreCase(image.role()) || image.data().length == 0) continue;
                ocr.requireImageWithinOcrLimit(image.data());
                var decoded = ImageIO.read(new ByteArrayInputStream(image.data()));
                if (decoded == null) continue;
                Path pageWork = Files.createDirectories(workDir.resolve("page-%04d".formatted(page.pageNumber())));
                Path raster = pageWork.resolve("image-%04d.png".formatted(++imageIndex));
                if (!ImageIO.write(decoded, "png", raster.toFile())) continue;
                progress.update(TaskStage.RECOGNIZING,
                        30 + (int) (page.pageNumber() * 35d / Math.max(1, parsed.sourcePageCount())));
                TesseractOcrConverter.RecognitionResult result = ocr.recognizeLayoutResult(raster,
                        pageWork.resolve("ocr-" + imageIndex), page.pageNumber(), image.box(), limits);
                for (TextBlock block : result.blocks()) {
                    recognized.add(new TextBlock(block.id() + "-i" + imageIndex, block.pageNumber(), block.box(),
                            block.text(), block.baselineY(), block.style(), recognized.size() + 1,
                            block.textOffsetXmm(), block.textOffsetYmm(), block.advancesMm(), block.transform()));
                }
                confidenceTotal += result.confidence() * result.wordCount();
                wordCount += result.wordCount();
            }
            TesseractOcrConverter.RecognitionResult pageResult = new TesseractOcrConverter.RecognitionResult(
                    recognized, wordCount == 0 ? 0d : confidenceTotal / wordCount, wordCount);
            ocr.requireUsableResult(pageResult, "OFD 第 " + page.pageNumber() + " 页");
            List<TextBlock> allTexts = new ArrayList<>(page.textBlocks());
            allTexts.addAll(recognized);
            List<ConversionWarning> warnings = page.warnings().stream()
                    .filter(warning -> warning.code() != WarningCode.OCR_REQUIRED)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            warnings.addAll(ocr.warningsFor(pageResult, page.pageNumber(),
                    "OFD 第 " + page.pageNumber() + " 页"));
            pages.add(new PageModel(page.pageNumber(), page.physicalBox(), allTexts, page.lines(), page.images(),
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
}
