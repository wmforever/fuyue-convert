package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PdfSplitConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_SPLIT,
            "将 PDF 按页拆分，并打包为 ZIP 下载。", QualityLevel.STABLE, ConversionStrategy.FIDELITY,
            List.of(), List.of("每个 PDF 页面生成一个独立文件"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        try (PDDocument source = Loader.loadPDF(input.path().toFile());
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputPath))) {
            List<PDDocument> pages = new Splitter().split(source);
            for (int index = 0; index < pages.size(); index++) {
                try (PDDocument page = pages.get(index)) {
                    zip.putNextEntry(new ZipEntry("page-%03d.pdf".formatted(index + 1)));
                    page.save(zip);
                    zip.closeEntry();
                }
                progress.update(TaskStage.RENDERING, 20 + (int) ((index + 1) * 60d / pageCount));
            }
        }
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", "-pages.zip"), pageCount, List.of());
    }
}
