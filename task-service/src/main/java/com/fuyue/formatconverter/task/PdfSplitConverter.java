package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PdfSplitConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_SPLIT,
            "将 PDF 全部或按指定页码范围拆分，并打包为 ZIP 下载。", QualityLevel.STABLE, ConversionStrategy.FIDELITY,
            List.of(), List.of("每个选中的 PDF 页面生成一个独立文件"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        List<Integer> selectedPages = input.options().splitPageNumbers(pageCount);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        try (PDDocument source = Loader.loadPDF(input.path().toFile());
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputPath))) {
            for (int index = 0; index < selectedPages.size(); index++) {
                int pageNumber = selectedPages.get(index);
                try (PDDocument page = new PDDocument()) {
                    page.importPage(source.getPage(pageNumber - 1));
                    zip.putNextEntry(new ZipEntry("page-%03d.pdf".formatted(pageNumber)));
                    page.save(zip);
                    zip.closeEntry();
                }
                progress.update(TaskStage.RENDERING, 20 + (int) ((index + 1) * 60d / selectedPages.size()));
            }
        }
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", "-pages.zip"),
                selectedPages.size(), List.of());
    }
}
