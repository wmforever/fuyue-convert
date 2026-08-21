package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Validates each merge input; the task service combines the successful PDFs in upload order. */
final class PdfMergeInputConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_MERGED,
            "按上传顺序将多个 PDF 合并为一个文件。", QualityLevel.STABLE, ConversionStrategy.FIDELITY,
            List.of(), List.of("至少上传两个 PDF；按上传顺序合并"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        int pages = ConversionGuards.requirePdfPageCount(input.path(), limits);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.copy(input.path(), outputPath);
        progress.update(TaskStage.RENDERING, 80);
        return new ConversionOutput(outputPath, input.displayName(), pages, List.of());
    }
}
