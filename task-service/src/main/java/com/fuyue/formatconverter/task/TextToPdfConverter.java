package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TextToPdfConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.TXT, DocumentFormat.PDF,
            "将 UTF-8 文本排版为基础 PDF。",
            QualityLevel.STABLE, ConversionStrategy.CONTENT, List.of(), List.of("仅支持基础文本排版"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        List<String> lines = Files.readAllLines(input.path(), StandardCharsets.UTF_8);
        progress.update(TaskStage.RENDERING, 80);
        PdfSupport.writeTextPdf(lines, outputPath);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "TXT 转 PDF");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.txt$", ".pdf"), null, List.of());
    }
}
