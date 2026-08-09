package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Path;
import java.util.List;

public final class TextToPdfConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.TXT, DocumentFormat.PDF,
            "将 UTF-8、带 BOM 的 UTF-16 或 GB18030 文本排版为基础 PDF，支持显式分页和 CJK 换行。",
            QualityLevel.STABLE, ConversionStrategy.CONTENT, List.of(),
            List.of("仅支持基础文本排版", "无 BOM 且非 UTF-8 的文本按 GB18030 解码并返回警告"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        TextInputReader.DecodedText decoded = TextInputReader.read(input.path(), limits);
        progress.update(TaskStage.RENDERING, 80);
        int pages = PdfSupport.writeTextPdfPages(decoded.pages(), outputPath, limits.maxPages());
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "TXT 转 PDF");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.txt$", ".pdf"),
                pages, decoded.warnings());
    }
}
