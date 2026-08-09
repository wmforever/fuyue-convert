package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.DocxRenderer;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.parser.SafeOfdPackage;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OfdToDocxConverter implements FileConverter {
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final DocxRenderer renderer;
    private final OfdOcrSupport ocr;
    private final ConversionRoute route;

    public OfdToDocxConverter(SafeOfdExtractor extractor, OfdParser parser,
                              PageLayoutAnalyzer analyzer, DocxRenderer renderer) {
        this(extractor, parser, analyzer, renderer, null);
    }

    OfdToDocxConverter(SafeOfdExtractor extractor, OfdParser parser,
                       PageLayoutAnalyzer analyzer, DocxRenderer renderer, OfdOcrSupport ocr) {
        this.extractor = extractor;
        this.parser = parser;
        this.analyzer = analyzer;
        this.renderer = renderer;
        this.ocr = ocr;
        this.route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.DOCX,
                ocr == null ? "将文字型 OFD 转换为可编辑 Word 文档，保留段落、表格、图片和页面方向。"
                        : "恢复 OFD 结构化对象，并将扫描图像文字识别为可编辑 Word 文字。",
                QualityLevel.BETA, ConversionStrategy.EDITABLE,
                ocr == null ? List.of() : List.of("tesseract"),
                List.of(ocr == null ? "扫描页未配置 OCR 时严格失败" : "OCR 页必须人工复核",
                        "复杂签章和厂商私有扩展需要更多样本验证"));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        List<ConversionWarning> warnings = new ArrayList<>();
        SafeOfdPackage safe = extractor.extract(input.path(), workDir, limits);
        progress.update(TaskStage.PARSING, 15);
        DocumentModel parsed = parser.parse(safe, input.displayName(), limits);
        if (ocr != null) {
            parsed = ocr.recognizeRequiredPages(parsed, workDir.resolve("ofd-ocr"), limits, progress);
        }
        List<Integer> ocrPages = parsed.pages().stream()
                .filter(page -> page.warnings().stream()
                        .anyMatch(warning -> warning.code() == WarningCode.OCR_REQUIRED))
                .map(PageModel::pageNumber).toList();
        if (!ocrPages.isEmpty()) {
            throw new ConversionFailureException("OCR_REQUIRED",
                    "第 " + ocrPages.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("")
                            + " 页包含扫描内容；当前未配置 OCR，未生成图片伪装的可编辑 DOCX");
        }
        warnings.addAll(parsed.warnings());
        progress.update(TaskStage.RECOGNIZING, 45);
        List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze).toList();
        pages.forEach(page -> warnings.addAll(page.warnings()));
        DocumentModel analyzed = new DocumentModel(parsed.sourceName(), parsed.parserName(),
                parsed.sourcePageCount(), pages, warnings);
        progress.update(TaskStage.RENDERING, 70);
        renderer.render(analyzed, outputPath);
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), parsed.pages().size(), warnings);
    }

    private String outputFileName(String input) {
        String base = input.replaceFirst("(?i)\\.ofd$", "");
        return base + "." + route.targetFormat().extension();
    }
}
