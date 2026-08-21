package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class PdfCompressConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_COMPRESSED,
            "重写 PDF 结构并使用压缩流优化体积，不降低图片清晰度。", QualityLevel.BETA, ConversionStrategy.FIDELITY,
            List.of(), List.of("无损优化效果取决于原始 PDF；不会降低图片分辨率"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        progress.update(TaskStage.PARSING, 30);
        try (PDDocument document = Loader.loadPDF(input.path().toFile())) {
            document.setAllSecurityToBeRemoved(true);
            document.save(outputPath.toFile());
        }
        progress.update(TaskStage.RENDERING, 80);
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", "-optimized.pdf"), pageCount, List.of());
    }
}
