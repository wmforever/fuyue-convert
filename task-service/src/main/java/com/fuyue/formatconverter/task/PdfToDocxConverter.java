package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.DocxRenderer;
import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PdfToDocxConverter implements FileConverter {
    private final PdfLayoutParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final DocxRenderer renderer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.DOCX,
            "将文字型 PDF 转换为可编辑 Word，恢复文字、基础段落、页面尺寸和方向。",
            QualityLevel.BETA, ConversionStrategy.EDITABLE, List.of(),
            List.of("扫描型 PDF 需要 OCR", "嵌入图片、复杂矢量图形、字体替代、阅读顺序和复杂表格仍需更多样本验证"));

    public PdfToDocxConverter() {
        this(new PdfLayoutParser(), new PageLayoutAnalyzer(), new PoiDocxRenderer());
    }

    public PdfToDocxConverter(PdfLayoutParser parser, PageLayoutAnalyzer analyzer, DocxRenderer renderer) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
        this.renderer = java.util.Objects.requireNonNull(renderer, "renderer");
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 20);
        DocumentModel parsed = parser.parse(input.path(), input.displayName(), limits);
        progress.update(TaskStage.RECOGNIZING, 50);
        List<ConversionWarning> warnings = new ArrayList<>(parsed.warnings());
        List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze).toList();
        pages.forEach(page -> warnings.addAll(page.warnings()));
        DocumentModel analyzed = new DocumentModel(parsed.sourceName(), parsed.parserName(),
                parsed.sourcePageCount(), pages, warnings);
        progress.update(TaskStage.RENDERING, 75);
        renderer.render(analyzed, outputPath);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 转 DOCX");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), parsed.sourcePageCount(), warnings);
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.pdf$", "") + ".docx";
    }
}
