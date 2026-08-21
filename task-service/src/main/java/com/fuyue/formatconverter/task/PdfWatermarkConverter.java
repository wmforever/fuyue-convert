package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class PdfWatermarkConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_WATERMARKED,
            "为 PDF 添加半透明 CONFIDENTIAL 文字水印。", QualityLevel.BETA, ConversionStrategy.FIDELITY,
            List.of(), List.of("首版使用固定英文水印；自定义文字和图片水印待后续开放"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        try (PDDocument document = Loader.loadPDF(input.path().toFile())) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDPage page = document.getPage(index);
                float width = page.getMediaBox().getWidth();
                float height = page.getMediaBox().getHeight();
                float size = Math.max(24f, Math.min(width, height) / 9f);
                try (PDPageContentStream content = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState state =
                            new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
                    state.setNonStrokingAlphaConstant(0.2f);
                    content.setGraphicsStateParameters(state);
                    content.setNonStrokingColor(new Color(150, 150, 150));
                    content.beginText();
                    content.setFont(font, size);
                    content.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(35), width * .22f, height * .42f));
                    content.showText("CONFIDENTIAL");
                    content.endText();
                }
                progress.update(TaskStage.RENDERING, 20 + (int) ((index + 1) * 60d / pageCount));
            }
            document.save(outputPath.toFile());
        }
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", "-watermarked.pdf"), pageCount, List.of());
    }
}
