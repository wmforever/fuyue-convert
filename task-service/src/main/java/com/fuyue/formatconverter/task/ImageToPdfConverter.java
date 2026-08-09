package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ImageToPdfConverter implements FileConverter {
    private static final float MAX_PDF_PAGE_POINTS = 14_400f;
    private static final float MIN_PDF_PAGE_POINTS = 3f;
    private final ConversionRoute route;

    public ImageToPdfConverter(DocumentFormat sourceFormat) {
        if (sourceFormat != DocumentFormat.PNG && sourceFormat != DocumentFormat.JPG) {
            throw new IllegalArgumentException("图片转 PDF 仅支持 PNG/JPG");
        }
        this.route = ConversionRoute.of(sourceFormat, DocumentFormat.PDF,
                "按图片像素、内嵌 DPI 和 EXIF 方向生成固定版式 PDF 页面。",
                QualityLevel.STABLE, ConversionStrategy.FIDELITY, List.of(),
                List.of("缺少或异常 DPI 时按 96 DPI 并返回警告", "同格式批量图片按上传顺序合并为多页 PDF"));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        ConversionGuards.requireImageBounds(input.path(), limits);
        ImageMetadataReader.ImageMetadata metadata = ImageMetadataReader.read(input.path(), route.sourceFormat());
        List<ConversionWarning> warnings = new ArrayList<>();
        if (!metadata.embeddedDpi()) {
            warnings.add(ConversionWarning.of(WarningCode.IMAGE_DPI_DEFAULTED,
                    "图片未包含可信的 36-1200 DPI 元数据，已按 96 DPI 生成页面。", 1));
        }
        if (metadata.orientation() != 1) {
            warnings.add(ConversionWarning.of(WarningCode.EXIF_ORIENTATION_APPLIED,
                    "已应用 EXIF Orientation=" + metadata.orientation() + "。", 1));
        }

        try (PDDocument document = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromFileByContent(input.path().toFile(), document);
            double destinationDpiX = metadata.swapsAxes() ? metadata.dpiY() : metadata.dpiX();
            double destinationDpiY = metadata.swapsAxes() ? metadata.dpiX() : metadata.dpiY();
            int orientedWidth = metadata.swapsAxes() ? image.getHeight() : image.getWidth();
            int orientedHeight = metadata.swapsAxes() ? image.getWidth() : image.getHeight();
            float pageWidth = (float) (orientedWidth * 72d / destinationDpiX);
            float pageHeight = (float) (orientedHeight * 72d / destinationDpiY);
            requirePageSize(pageWidth, pageHeight);
            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            document.addPage(page);
            progress.update(TaskStage.RENDERING, 75);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, imageMatrix(metadata, image.getWidth(), image.getHeight()));
            }
            document.save(outputPath.toFile());
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "图片转 PDF");
        return new ConversionOutput(outputPath,
                input.displayName().replaceFirst("(?i)\\.(png|jpe?g)$", ".pdf"), 1, warnings);
    }

    private Matrix imageMatrix(ImageMetadataReader.ImageMetadata metadata, int width, int height) {
        float[] orientation = switch (metadata.orientation()) {
            case 2 -> new float[]{-1, 0, 0, 1, width, 0};
            case 3 -> new float[]{-1, 0, 0, -1, width, height};
            case 4 -> new float[]{1, 0, 0, -1, 0, height};
            case 5 -> new float[]{0, -1, -1, 0, height, width};
            case 6 -> new float[]{0, -1, 1, 0, 0, width};
            case 7 -> new float[]{0, 1, 1, 0, 0, 0};
            case 8 -> new float[]{0, 1, -1, 0, height, 0};
            default -> new float[]{1, 0, 0, 1, 0, 0};
        };
        float destinationDpiX = (float) (metadata.swapsAxes() ? metadata.dpiY() : metadata.dpiX());
        float destinationDpiY = (float) (metadata.swapsAxes() ? metadata.dpiX() : metadata.dpiY());
        float scaleX = 72f / destinationDpiX;
        float scaleY = 72f / destinationDpiY;
        return new Matrix(orientation[0] * width * scaleX,
                orientation[1] * width * scaleY,
                orientation[2] * height * scaleX,
                orientation[3] * height * scaleY,
                orientation[4] * scaleX,
                orientation[5] * scaleY);
    }

    private void requirePageSize(float width, float height) throws ConversionFailureException {
        if (!Float.isFinite(width) || !Float.isFinite(height)
                || width < MIN_PDF_PAGE_POINTS || height < MIN_PDF_PAGE_POINTS
                || width > MAX_PDF_PAGE_POINTS || height > MAX_PDF_PAGE_POINTS) {
            throw new ConversionFailureException("PAGE_SIZE_UNSUPPORTED",
                    "图片 DPI 对应的 PDF 页面尺寸超出 3-14400 pt 安全范围：" + width + " x " + height);
        }
    }
}
