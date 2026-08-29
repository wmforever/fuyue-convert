package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class PdfWatermarkConverter implements FileConverter {
    private static final String LATIN_FONT = "/fonts/LiberationSans-Regular.ttf";
    private static final String CJK_FONT = "/fonts/DroidSansFallback.ttf";
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PDF_WATERMARKED,
            "为 PDF 添加可配置的中英文文字水印，支持透明度、角度、位置、平铺和页码范围。",
            QualityLevel.BETA, ConversionStrategy.FIDELITY, List.of(),
            List.of("修改带数字签名的 PDF 会导致签名失效，因此当前严格拒绝处理"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        ConversionOptions options = input.options();
        int markedPages = 0;
        progress.update(TaskStage.PARSING, 15);
        try (PDDocument document = Loader.loadPDF(input.path().toFile())) {
            requireUnsigned(document);
            FontSet fonts = loadFonts(document);
            Color color = Color.decode(options.watermarkColor());
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                int pageNumber = index + 1;
                if (!options.appliesWatermarkToPage(pageNumber)) continue;
                addWatermark(document, document.getPage(index), fonts, color, options);
                markedPages++;
                progress.update(TaskStage.RENDERING, 20 + (int) (index + 1L) * 60 / pageCount);
            }
            if (markedPages == 0) {
                throw new ConversionFailureException("WATERMARK_PAGE_RANGE_EMPTY",
                        "指定页码范围没有匹配 PDF 中的任何页面");
            }
            document.save(outputPath.toFile());
        }
        validateOutput(outputPath, pageCount, limits);
        progress.update(TaskStage.RENDERING, 88);
        return new ConversionOutput(outputPath,
                input.displayName().replaceFirst("(?i)\\.pdf$", "-watermarked.pdf"), pageCount, List.of());
    }

    private void addWatermark(PDDocument document, PDPage page, FontSet fonts, Color color,
                              ConversionOptions options) throws IOException {
        PDRectangle box = page.getCropBox();
        float width = box.getWidth();
        float height = box.getHeight();
        float fontSize = fittedFontSize(fonts, options.watermarkText(), width, height,
                options.watermarkTiled() ? 0.24f : 0.70f);
        PDExtendedGraphicsState state = new PDExtendedGraphicsState();
        state.setNonStrokingAlphaConstant(options.watermarkOpacity().floatValue());
        try (PDPageContentStream content = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            content.saveGraphicsState();
            content.setGraphicsStateParameters(state);
            content.setNonStrokingColor(color);
            if (options.watermarkTiled()) {
                addTiled(content, box, fonts, fontSize, options);
            } else {
                Point position = position(box, fonts, fontSize, options.watermarkText(), options.watermarkPosition());
                showText(content, fonts, fontSize, options.watermarkText(), position.x(), position.y(),
                        options.watermarkAngle());
            }
            content.restoreGraphicsState();
        } catch (IllegalArgumentException e) {
            throw new ConversionFailureException("WATERMARK_TEXT_UNSUPPORTED",
                    "水印文字包含当前内置字体无法显示的字符");
        }
    }

    private void addTiled(PDPageContentStream content, PDRectangle box, FontSet fonts, float fontSize,
                          ConversionOptions options) throws IOException {
        int columns = 3;
        int rows = 4;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                float x = box.getLowerLeftX() + box.getWidth() * (column + .5f) / columns;
                float y = box.getLowerLeftY() + box.getHeight() * (row + .5f) / rows;
                showText(content, fonts, fontSize, options.watermarkText(), x, y, options.watermarkAngle());
            }
        }
    }

    private void showText(PDPageContentStream content, FontSet fonts, float fontSize, String text,
                          float x, float y, double angle) throws IOException {
        float textWidth = textWidth(fonts, text, fontSize);
        content.beginText();
        content.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle), x, y));
        content.newLineAtOffset(-textWidth / 2f, -fontSize / 3f);
        PDFont active = null;
        StringBuilder run = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            PDFont font = fontFor(codePoint, fonts);
            if (active != font) {
                showRun(content, active, fontSize, run);
                active = font;
            }
            run.appendCodePoint(codePoint);
        }
        showRun(content, active, fontSize, run);
        content.endText();
    }

    private void showRun(PDPageContentStream content, PDFont font, float fontSize, StringBuilder run)
            throws IOException {
        if (font == null || run.isEmpty()) return;
        content.setFont(font, fontSize);
        content.showText(run.toString());
        run.setLength(0);
    }

    private float fittedFontSize(FontSet fonts, String text, float width, float height, float widthRatio)
            throws IOException {
        float size = Math.max(18f, Math.min(76f, Math.min(width, height) / 9f));
        float measured = textWidth(fonts, text, size);
        if (measured > width * widthRatio) size *= width * widthRatio / measured;
        return Math.max(11f, size);
    }

    private Point position(PDRectangle box, FontSet fonts, float fontSize, String text,
                           WatermarkPosition position) throws IOException {
        float textWidth = textWidth(fonts, text, fontSize);
        float padding = Math.max(24f, Math.min(box.getWidth(), box.getHeight()) * .06f);
        float centerX = box.getLowerLeftX() + box.getWidth() / 2f;
        float centerY = box.getLowerLeftY() + box.getHeight() / 2f;
        return switch (position) {
            case CENTER -> new Point(centerX, centerY);
            case TOP_LEFT -> new Point(box.getLowerLeftX() + padding + textWidth / 2f,
                    box.getUpperRightY() - padding - fontSize / 2f);
            case TOP_RIGHT -> new Point(box.getUpperRightX() - padding - textWidth / 2f,
                    box.getUpperRightY() - padding - fontSize / 2f);
            case BOTTOM_LEFT -> new Point(box.getLowerLeftX() + padding + textWidth / 2f,
                    box.getLowerLeftY() + padding + fontSize / 2f);
            case BOTTOM_RIGHT -> new Point(box.getUpperRightX() - padding - textWidth / 2f,
                    box.getLowerLeftY() + padding + fontSize / 2f);
        };
    }

    private float textWidth(FontSet fonts, String text, float fontSize) throws IOException {
        float width = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            PDFont font = fontFor(codePoint, fonts);
            width += font.getStringWidth(new String(Character.toChars(codePoint))) * fontSize / 1000f;
        }
        return width;
    }

    private PDFont fontFor(int codePoint, FontSet fonts) {
        return codePoint <= 0x7f ? fonts.latin() : fonts.cjk();
    }

    private FontSet loadFonts(PDDocument document) throws IOException {
        return new FontSet(loadFont(document, LATIN_FONT), loadFont(document, CJK_FONT));
    }

    private PDType0Font loadFont(PDDocument document, String resource) throws IOException {
        try (InputStream input = PdfWatermarkConverter.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("内置水印字体缺失：" + resource);
            return PDType0Font.load(document, input);
        }
    }

    private void requireUnsigned(PDDocument document) throws IOException {
        if (!document.getSignatureDictionaries().isEmpty()) {
            throw new ConversionFailureException("PDF_SIGNATURE_PRESENT",
                    "PDF 包含数字签名；添加水印会使签名失效，已拒绝处理");
        }
    }

    private void validateOutput(Path output, int expectedPages, ParseLimits limits) throws IOException {
        ConversionGuards.requireNonEmptyOutputFile(output, limits, "PDF 水印");
        int actualPages = ConversionGuards.requirePdfPageCount(output, limits);
        if (actualPages != expectedPages) throw new IOException("水印 PDF 页数不一致");
    }

    private record Point(float x, float y) { }
    private record FontSet(PDType0Font latin, PDType0Font cjk) { }
}
