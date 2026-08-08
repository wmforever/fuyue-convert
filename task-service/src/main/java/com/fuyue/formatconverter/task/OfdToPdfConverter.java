package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OfdToPdfConverter implements FileConverter {
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.PDF,
            "将文字型 OFD 文本内容导出为基础 PDF。",
            QualityLevel.BETA, ConversionStrategy.CONTENT, List.of(), List.of("当前为基础文本 PDF，不是完整 OFD 版式渲染"));

    public OfdToPdfConverter(SafeOfdExtractor extractor, OfdParser parser, PageLayoutAnalyzer analyzer) {
        this.extractor = extractor;
        this.parser = parser;
        this.analyzer = analyzer;
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 20);
        var safe = extractor.extract(input.path(), workDir, limits);
        var parsed = parser.parse(safe, input.displayName(), limits);
        progress.update(TaskStage.RECOGNIZING, 55);
        List<String> lines = new ArrayList<>();
        parsed.pages().stream().map(analyzer::analyze).forEach(page -> {
            if (parsed.pages().size() > 1) lines.add("第 " + page.pageNumber() + " 页");
            if (!page.paragraphs().isEmpty()) {
                page.paragraphs().forEach(paragraph ->
                        lines.add(paragraph.runs().stream().map(TextBlock::text).reduce("", String::concat)));
            } else {
                page.textBlocks().stream()
                        .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(block -> block.box().x()))
                        .map(TextBlock::text).forEach(lines::add);
            }
            lines.add("");
        });
        progress.update(TaskStage.RENDERING, 80);
        PdfSupport.writeTextPdf(lines, outputPath);
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.ofd$", ".pdf"),
                parsed.pages().size(), parsed.warnings());
    }
}
