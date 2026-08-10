package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.DocxRenderer;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Explicit image-to-editable-Word OCR route backed by the shared DocumentModel pipeline. */
final class ImageOcrToDocxConverter implements FileConverter {
    private final DocumentFormat sourceFormat;
    private final TesseractOcrConverter ocr;
    private final PageLayoutAnalyzer analyzer;
    private final DocxRenderer renderer;
    private final ConversionRoute route;

    ImageOcrToDocxConverter(DocumentFormat sourceFormat, TesseractOcrConverter.Settings settings,
                            PageLayoutAnalyzer analyzer, DocxRenderer renderer) {
        this.sourceFormat = sourceFormat;
        this.ocr = new TesseractOcrConverter(sourceFormat, settings);
        this.analyzer = analyzer;
        this.renderer = renderer;
        this.route = ConversionRoute.of(sourceFormat, DocumentFormat.DOCX,
                "明确使用本地 Tesseract OCR，将图片文字和坐标映射到 DocumentModel 后生成可编辑 Word。",
                QualityLevel.EXPERIMENTAL, ConversionStrategy.EDITABLE, List.of("tesseract"),
                List.of("OCR 结果包含页级置信度和警告，必须人工复核", "不把整页图片伪装成可编辑文字"));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        ConversionGuards.requireImageBounds(input.path(), limits);
        ocr.requireImageWithinOcrLimit(input.path());
        progress.update(TaskStage.PARSING, 15);
        OcrImageNormalizer.Prepared prepared = OcrImageNormalizer.prepare(input.path(), sourceFormat,
                workDir.resolve("normalized"));
        double dpiX = prepared.metadata().swapsAxes() ? prepared.metadata().dpiY() : prepared.metadata().dpiX();
        double dpiY = prepared.metadata().swapsAxes() ? prepared.metadata().dpiX() : prepared.metadata().dpiY();
        Rect pageBox = new Rect(0d, 0d, prepared.width() * 25.4d / dpiX,
                prepared.height() * 25.4d / dpiY);
        if (pageBox.width() > 558.8d || pageBox.height() > 558.8d) {
            throw new ConversionFailureException("PAGE_SIZE_UNSUPPORTED",
                    "图片 DPI 对应的 OCR Word 页面超过 22 英寸上限");
        }
        progress.update(TaskStage.RECOGNIZING, 35);
        TesseractOcrConverter.RecognitionResult result = ocr.recognizeLayoutResult(prepared.path(),
                workDir.resolve("ocr"), 1, pageBox, limits);
        ocr.requireUsableResult(result, "图片第 1 页");
        List<ConversionWarning> warnings = new ArrayList<>(ocr.warningsFor(result, 1, "图片第 1 页"));
        if (!prepared.metadata().embeddedDpi()) {
            warnings.add(ConversionWarning.of(WarningCode.IMAGE_DPI_DEFAULTED,
                    "图片未包含可信 DPI，OCR Word 页面按 96 DPI 计算。", 1));
        }
        if (prepared.orientationApplied()) {
            warnings.add(ConversionWarning.of(WarningCode.EXIF_ORIENTATION_APPLIED,
                    "OCR 前已应用 EXIF Orientation=" + prepared.metadata().orientation() + "。", 1));
        }
        PageModel page = analyzer.analyze(new PageModel(1, pageBox, result.blocks(), List.of(), List.of(),
                List.of(), List.of(), warnings));
        DocumentModel document = new DocumentModel(input.displayName(), "Tesseract OCR", 1,
                List.of(page), warnings);
        progress.update(TaskStage.RENDERING, 75);
        renderer.render(document, outputPath);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "图片 OCR 转 DOCX");
        return new ConversionOutput(outputPath,
                input.displayName().replaceFirst("(?i)\\.(png|jpe?g)$", ".docx"), 1, warnings);
    }
}
