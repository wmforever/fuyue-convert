package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.DocxRenderer;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
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
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.DOCX,
            "将文字型 OFD 转换为可编辑 Word 文档，保留段落、表格、图片和页面方向。",
            QualityLevel.BETA, ConversionStrategy.EDITABLE, List.of(), List.of("复杂签章、扫描页和厂商私有扩展需要更多样本验证"));

    public OfdToDocxConverter(SafeOfdExtractor extractor, OfdParser parser,
                              PageLayoutAnalyzer analyzer, DocxRenderer renderer) {
        this.extractor = extractor;
        this.parser = parser;
        this.analyzer = analyzer;
        this.renderer = renderer;
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        List<ConversionWarning> warnings = new ArrayList<>();
        SafeOfdPackage safe = extractor.extract(input.path(), workDir, limits);
        progress.update(TaskStage.PARSING, 15);
        DocumentModel parsed = parser.parse(safe, input.displayName(), limits);
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
