package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.parser.SafeOfdPackage;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OfdToTextConverter implements FileConverter {
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.TXT,
            "提取文字型 OFD 的可编辑文本，按页面和段落顺序输出 UTF-8 文本。",
            QualityLevel.BETA, ConversionStrategy.EXTRACTION, List.of(), List.of("扫描型 OFD 需要 OCR，当前不识别图片文字"));

    public OfdToTextConverter(SafeOfdExtractor extractor, OfdParser parser, PageLayoutAnalyzer analyzer) {
        this.extractor = extractor;
        this.parser = parser;
        this.analyzer = analyzer;
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
        progress.update(TaskStage.RECOGNIZING, 55);
        List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze).toList();
        pages.forEach(page -> warnings.addAll(page.warnings()));
        progress.update(TaskStage.RENDERING, 80);
        Files.writeString(outputPath, text(pages), StandardCharsets.UTF_8);
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), parsed.pages().size(), warnings);
    }

    private String text(List<PageModel> pages) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            PageModel page = pages.get(i);
            if (i > 0) out.append(System.lineSeparator()).append(System.lineSeparator());
            if (pages.size() > 1) out.append("第 ").append(page.pageNumber()).append(" 页").append(System.lineSeparator());
            if (!page.paragraphs().isEmpty()) {
                page.paragraphs().forEach(paragraph -> {
                    String line = paragraph.runs().stream().map(TextBlock::text).reduce("", String::concat).strip();
                    if (!line.isEmpty()) out.append(line).append(System.lineSeparator());
                });
            } else {
                page.textBlocks().stream()
                        .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(block -> block.box().x()))
                        .map(TextBlock::text)
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .forEach(line -> out.append(line).append(System.lineSeparator()));
            }
        }
        return out.toString();
    }

    private String outputFileName(String input) {
        String base = input.replaceFirst("(?i)\\.ofd$", "");
        return base + "." + route.targetFormat().extension();
    }
}
