package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PdfToTextConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.TXT,
            "从文字型 PDF 提取文本为 UTF-8 TXT。",
            QualityLevel.STABLE, ConversionStrategy.EXTRACTION, List.of(), List.of("扫描型 PDF 需要 OCR，当前不识别图片文字"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        ConversionGuards.requirePdfPageCount(input.path(), limits);
        String text;
        try (var document = Loader.loadPDF(input.path().toFile())) {
            text = new PDFTextStripper().getText(document);
        }
        progress.update(TaskStage.RENDERING, 80);
        Files.writeString(outputPath, text, StandardCharsets.UTF_8);
        ConversionGuards.requireOutputFile(outputPath, limits, "PDF 转 TXT");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", ".txt"), null, List.of());
    }
}
