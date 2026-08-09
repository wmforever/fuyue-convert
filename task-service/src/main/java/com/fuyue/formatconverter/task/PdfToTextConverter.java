package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PdfToTextConverter implements FileConverter {
    private final PdfLayoutParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.TXT,
            "按页面坐标和多栏阅读顺序从文字型 PDF 提取 UTF-8 文本。",
            QualityLevel.BETA, ConversionStrategy.EXTRACTION, List.of(),
            List.of("扫描型或含无文字扫描页的 PDF 在未配置 OCR 时严格失败", "复杂旋转文字和无框表格阅读顺序仍需更多样本"));

    public PdfToTextConverter() { this(new PdfLayoutParser(), new PageLayoutAnalyzer()); }

    PdfToTextConverter(PdfLayoutParser parser, PageLayoutAnalyzer analyzer) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        var parsed = parser.parseForTextExtraction(input.path(), input.displayName(), limits);
        progress.update(TaskStage.RECOGNIZING, 60);
        var pages = parsed.pages().stream().map(analyzer::analyze).toList();
        List<ConversionWarning> warnings = new ArrayList<>(parsed.warnings());
        pages.forEach(page -> warnings.addAll(page.warnings()));
        progress.update(TaskStage.RENDERING, 80);
        Files.writeString(outputPath, OfdToTextConverter.text(pages), StandardCharsets.UTF_8);
        ConversionGuards.requireOutputFile(outputPath, limits, "PDF 转 TXT");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", ".txt"),
                parsed.sourcePageCount(), warnings);
    }
}
