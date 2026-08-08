package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.nio.file.Path;
import java.util.List;

public final class ImageToPdfConverter implements FileConverter {
    private final ConversionRoute route;

    public ImageToPdfConverter(DocumentFormat sourceFormat) {
        if (sourceFormat != DocumentFormat.PNG && sourceFormat != DocumentFormat.JPG) {
            throw new IllegalArgumentException("图片转 PDF 仅支持 PNG/JPG");
        }
        this.route = ConversionRoute.of(sourceFormat, DocumentFormat.PDF,
                "将图片按原始比例放入单页 PDF。");
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        ConversionGuards.requireImageBounds(input.path(), limits);
        try (PDDocument document = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromFileByContent(input.path().toFile(), document);
            PDRectangle pageSize = new PDRectangle(image.getWidth(), image.getHeight());
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            progress.update(TaskStage.RENDERING, 75);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0, image.getWidth(), image.getHeight());
            }
            document.save(outputPath.toFile());
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "图片转 PDF");
        return new ConversionOutput(outputPath,
                input.displayName().replaceFirst("(?i)\\.(png|jpe?g)$", ".pdf"), null, List.of());
    }
}
