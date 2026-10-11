package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.cos.COSName;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class PdfSplitConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_SPLIT,
            "将 PDF 全部或按指定页码范围拆分，并打包为 ZIP 下载。", QualityLevel.STABLE, ConversionStrategy.FIDELITY,
            List.of(), List.of("每个选中的 PDF 页面生成一个独立文件",
                    "拆分会重写 PDF；带数字签名的输入会被严格拒绝"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        List<Integer> selectedPages = input.options().splitPageNumbers(pageCount);
        Files.createDirectories(workDir);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        try (PDDocument source = Loader.loadPDF(input.path().toFile())) {
            ConversionGuards.requireUnsignedPdf(source, "拆分重写");
            try (LimitedZipOutput zip = LimitedZipOutput.open(outputPath, limits, "PDF 拆分 ZIP")) {
                for (int index = 0; index < selectedPages.size(); index++) {
                    int pageNumber = selectedPages.get(index);
                    Path pageFile = Files.createTempFile(workDir, "split-page-", ".pdf");
                    try (PDDocument page = new PDDocument()) {
                        PDPage sourcePage = source.getPage(pageNumber - 1);
                        PDPage importedPage = page.importPage(sourcePage);
                        // PDFBox does not copy resources inherited from the page tree. A
                        // page using a font or image declared on /Pages would otherwise
                        // open successfully but render/extract as a blank page after split.
                        if (sourcePage.getResources() != null
                                && !sourcePage.getCOSObject().containsKey(COSName.RESOURCES)
                                && importedPage.getResources() == null) {
                            importedPage.setResources(sourcePage.getResources());
                        }
                        try (OutputStream pageOutput = new LimitedOutputStream(
                                Files.newOutputStream(pageFile), limits.maxEntryBytes(), "PDF 拆分页")) {
                            page.save(pageOutput);
                        }
                        zip.addFile("page-%03d.pdf".formatted(pageNumber), pageFile);
                    } finally {
                        Files.deleteIfExists(pageFile);
                    }
                    progress.update(TaskStage.RENDERING, 20 + (int) ((index + 1) * 60d / selectedPages.size()));
                }
                zip.finish();
            }
        }
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", "-pages.zip"),
                selectedPages.size(), List.of());
    }
}
