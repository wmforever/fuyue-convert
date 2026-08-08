package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class OfficeToPdfBackedDocxConverter implements FileConverter {
    private final ConversionRoute route;
    private final LibreOfficeConverter officeToPdf;
    private final PdfToDocxConverter pdfToDocx;

    public OfficeToPdfBackedDocxConverter(DocumentFormat sourceFormat, Path officeBinary, Duration timeout,
                                          String description) {
        if (sourceFormat == DocumentFormat.PDF) {
            throw new IllegalArgumentException("PDF 源格式应直接使用 PdfToDocxConverter");
        }
        this.route = ConversionRoute.of(sourceFormat, DocumentFormat.DOCX, description);
        this.officeToPdf = new LibreOfficeConverter(sourceFormat, DocumentFormat.PDF, officeBinary, timeout,
                "先使用 LibreOffice headless 渲染为 PDF。");
        this.pdfToDocx = new PdfToDocxConverter();
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        Path pdfPath = workDir.resolve("source-rendered.pdf");
        progress.update(TaskStage.RENDERING, 20);
        ConversionOutput pdf = officeToPdf.convert(input, workDir.resolve("office-pdf"), pdfPath, limits,
                (stage, value) -> progress.update(stage, Math.min(60, Math.max(20, value / 2))));
        progress.update(TaskStage.RENDERING, 65);
        ConversionInput pdfInput = new ConversionInput(replaceExtension(input.displayName(), "pdf"),
                DocumentFormat.PDF.contentType(), Files.size(pdf.path()), pdf.path());
        ConversionOutput docx = pdfToDocx.convert(pdfInput, workDir.resolve("pdf-docx"), outputPath, limits,
                (stage, value) -> progress.update(stage, 60 + Math.min(35, value / 3)));
        List<ConversionWarning> warnings = new ArrayList<>(pdf.warnings());
        warnings.addAll(docx.warnings());
        warnings.add(ConversionWarning.of(WarningCode.FIDELITY_IMAGE_LAYER,
                "已使用 PDF 页面图生成保真优先 DOCX，正文结构编辑能力有限。", null));
        return new ConversionOutput(outputPath, replaceExtension(input.displayName(), "docx"),
                docx.pageCount(), warnings);
    }

    private String replaceExtension(String input, String extension) {
        String file = Path.of(input).getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        return base + "." + extension;
    }
}
