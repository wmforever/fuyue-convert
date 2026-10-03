package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.DocxRenderer;
import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PdfToDocxConverter implements FileConverter {
    private final PdfLayoutParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final DocxRenderer renderer;
    private final PdfOcrSupport ocr;
    private final ConversionRoute route;

    public PdfToDocxConverter() {
        this(new PdfLayoutParser(), new PageLayoutAnalyzer(), new PoiDocxRenderer(), null);
    }

    public PdfToDocxConverter(PdfLayoutParser parser, PageLayoutAnalyzer analyzer, DocxRenderer renderer) {
        this(parser, analyzer, renderer, null);
    }

    PdfToDocxConverter(PdfLayoutParser parser, PageLayoutAnalyzer analyzer, DocxRenderer renderer,
                       PdfOcrSupport ocr) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
        this.renderer = java.util.Objects.requireNonNull(renderer, "renderer");
        this.ocr = ocr;
        this.route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.DOCX,
                ocr == null ? "将文字型 PDF 转换为可编辑 Word，恢复基础段落、有线规则表格、明显双栏布局、页面尺寸和方向；明确的连续纯正文可跨页续接。"
                        : "恢复 PDF 真实文字和明确的连续正文，并以本地 Tesseract 叠加可编辑文字，同时保留扫描源图防止漏内容。",
                QualityLevel.BETA, ConversionStrategy.EDITABLE,
                ocr == null ? List.of() : List.of("tesseract"),
                List.of(ocr == null ? "扫描型 PDF 需要 OCR" : "OCR 页保留扫描图层且文字必须人工复核",
                        "明显双栏使用可编辑定位文本框；窄栏沟、混合阅读顺序、复杂表格、矢量图形及图片仍需更多样本验证"));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 20);
        DocumentModel parsed = ocr == null
                ? parser.parse(input.path(), input.displayName(), limits)
                : parser.parseForEditableOcr(input.path(), input.displayName(), limits);
        if (ocr != null) {
            parsed = ocr.recognizeMissingPages(input.path(), parsed, workDir.resolve("pdf-ocr"), limits, progress);
        }
        progress.update(TaskStage.RECOGNIZING, 50);
        List<ConversionWarning> warnings = new ArrayList<>(parsed.warnings());
        PdfParagraphReconstructor paragraphs = new PdfParagraphReconstructor();
        List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze).map(paragraphs::reconstruct).toList();
        pages.forEach(page -> {
            warnings.addAll(page.warnings());
            if (page.textBlocks().stream().anyMatch(block -> {
                double angle = Math.abs(block.transform().rotationDegrees());
                return angle > 0.5d && Math.abs(angle - 90d) >= 0.01d;
            })) {
                warnings.add(ConversionWarning.of(WarningCode.UNSUPPORTED_TEXT_TRANSFORM,
                        "页面含 180° 或非直角旋转文字；已保留可编辑文字和角度，但部分 Office 阅读器可能仍显示为横排。",
                        page.pageNumber()));
            }
        });
        DocumentModel analyzed = new DocumentModel(parsed.sourceName(), parsed.parserName(),
                parsed.sourcePageCount(), pages, warnings);
        analyzed = paragraphs.reconstructAcrossPages(analyzed);
        progress.update(TaskStage.RENDERING, 75);
        renderer.render(analyzed, outputPath);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 转 DOCX");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), parsed.sourcePageCount(), warnings);
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.pdf$", "") + ".docx";
    }

}
