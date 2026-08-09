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

    OfdOcrSupport(TesseractOcrConverter.Settings settings) {
        this.ocr = new TesseractOcrConverter(DocumentFormat.PNG, settings);
    }

    DocumentModel recognizeRequiredPages(DocumentModel parsed, Path workDir, ParseLimits limits,
                                         ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        List<PageModel> pages = new ArrayList<>(parsed.pages().size());
        for (PageModel page : parsed.pages()) {
            if (!requiresOcr(page)) {
                pages.add(page);
                continue;
            }
            List<TextBlock> recognized = new ArrayList<>();
            int imageIndex = 0;
            for (ImageBlock image : page.images()) {
                if ("SIGNATURE".equalsIgnoreCase(image.role()) || image.data().length == 0) continue;
                var decoded = ImageIO.read(new ByteArrayInputStream(image.data()));
                if (decoded == null) continue;
                Path pageWork = Files.createDirectories(workDir.resolve("page-%04d".formatted(page.pageNumber())));
                Path raster = pageWork.resolve("image-%04d.png".formatted(++imageIndex));
                if (!ImageIO.write(decoded, "png", raster.toFile())) continue;
                progress.update(TaskStage.RECOGNIZING,
                        30 + (int) (page.pageNumber() * 35d / Math.max(1, parsed.sourcePageCount())));
                recognized.addAll(ocr.recognizeLayout(raster, pageWork.resolve("ocr-" + imageIndex),
                        page.pageNumber(), image.box(), limits));
            }
            if (recognized.isEmpty()) {
                throw new ConversionFailureException("OCR_NO_TEXT",
                        "OFD 第 " + page.pageNumber() + " 页包含扫描图像，但 OCR 未识别到文字；未生成不完整结果。");
            }
            List<TextBlock> allTexts = new ArrayList<>(page.textBlocks());
            allTexts.addAll(recognized);
            List<ConversionWarning> warnings = page.warnings().stream()
                    .filter(warning -> warning.code() != WarningCode.OCR_REQUIRED)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            warnings.add(ConversionWarning.of(WarningCode.OCR_APPLIED,
                    "OFD 第 " + page.pageNumber() + " 页已使用本地 Tesseract OCR，结果必须人工复核。",
                    page.pageNumber()));
            pages.add(new PageModel(page.pageNumber(), page.physicalBox(), allTexts, page.lines(), page.images(),
                    List.of(), List.of(), warnings));
        }
        return new DocumentModel(parsed.sourceName(), parsed.parserName() + " + Tesseract",
                parsed.sourcePageCount(), pages, parsed.warnings());
    }

    private boolean requiresOcr(PageModel page) {
        return page.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_REQUIRED);
    }
}
