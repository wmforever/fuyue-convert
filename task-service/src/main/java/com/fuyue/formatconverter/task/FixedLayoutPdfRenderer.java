package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.LineElement;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TextBlock;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Paints the shared top-left, millimetre-based layout model into a fixed-layout PDF. */
final class FixedLayoutPdfRenderer {
    private static final double POINTS_PER_MM = 72d / 25.4d;
    private static final String LATIN_FONT = "/fonts/LiberationSans-Regular.ttf";
    private static final String CJK_FONT = "/fonts/DroidSansFallback.ttf";

    void render(DocumentModel model, Path outputPath) throws IOException {
        if (model.pages().isEmpty()) throw new IOException("文档没有可渲染页面");
        try (PDDocument document = new PDDocument()) {
            FontSet fonts = new FontSet(loadFont(document, LATIN_FONT), loadFont(document, CJK_FONT));
            for (PageModel sourcePage : model.pages()) {
                renderPage(document, sourcePage, fonts);
            }
            document.save(outputPath.toFile());
        }
    }

    private void renderPage(PDDocument document, PageModel sourcePage, FontSet fonts) throws IOException {
        float width = points(sourcePage.physicalBox().width());
        float height = points(sourcePage.physicalBox().height());
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
            throw new IOException("OFD 第 " + sourcePage.pageNumber() + " 页尺寸无效");
        }
        PDPage page = new PDPage(new PDRectangle(width, height));
        document.addPage(page);
        List<RenderItem> items = new ArrayList<>();
        sourcePage.lines().forEach(line -> items.add(RenderItem.line(line)));
        sourcePage.images().forEach(image -> items.add(RenderItem.image(image)));
        sourcePage.textBlocks().forEach(text -> items.add(RenderItem.text(text)));
        items.sort(Comparator.comparingInt(RenderItem::zOrder).thenComparingInt(RenderItem::kind));

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            for (RenderItem item : items) {
                if (item.line() != null) drawLine(content, sourcePage, item.line());
                else if (item.image() != null) drawImage(document, content, sourcePage, item.image());
                else if (item.text() != null) drawText(content, sourcePage, item.text(), fonts);
            }
        }
    }

    private void drawLine(PDPageContentStream content, PageModel page, LineElement line) throws IOException {
        content.saveGraphicsState();
        content.setStrokingColor(color(line.color()));
        content.setLineWidth(Math.max(0.1f, points(line.widthMm())));
        content.moveTo(x(page, line.start().x()), y(page, line.start().y()));
        content.lineTo(x(page, line.end().x()), y(page, line.end().y()));
        content.stroke();
        content.restoreGraphicsState();
    }

    private void drawImage(PDDocument document, PDPageContentStream content,
                           PageModel page, ImageBlock image) throws IOException {
        if (image.data().length == 0 || image.box().width() <= 0 || image.box().height() <= 0) return;
        PDImageXObject object = PDImageXObject.createFromByteArray(document, image.data(), image.id());
        float left = x(page, image.box().x());
        float bottom = y(page, image.box().bottom());
        content.drawImage(object, left, bottom, points(image.box().width()), points(image.box().height()));
    }

    private void drawText(PDPageContentStream content, PageModel page,
                          TextBlock block, FontSet fonts) throws IOException {
        if (block.text().isEmpty()) return;
        float anchorX = x(page, block.box().x() + block.textOffsetXmm());
        float anchorY = y(page, block.baselineY());
        double rotation = -Math.toRadians(block.transform().rotationDegrees());
        double verticalScale = Math.max(0.01d, block.transform().scaleY());
        double horizontalRatio = Math.max(0.01d, Math.min(10d,
                block.transform().scaleX() / verticalScale));
        float horizontalScale = (float) (horizontalRatio * 100d);
        float fontSize = Math.max(1f, (float) block.style().sizePt());

        content.beginText();
        content.setNonStrokingColor(color(block.style().color()));
        content.setHorizontalScaling(horizontalScale);
        content.setCharacterSpacing(characterSpacing(block, fonts, fontSize, horizontalRatio));
        content.setTextMatrix(Matrix.getRotateInstance(rotation, anchorX, anchorY));
        writeFontRuns(content, block.text(), fontSize, fonts);
        content.endText();
    }

    private float characterSpacing(TextBlock block, FontSet fonts,
                                   float fontSize, double horizontalRatio) {
        int[] codePoints = block.text().codePoints().toArray();
        int gaps = Math.min(block.advancesMm().size(), Math.max(0, codePoints.length - 1));
        if (gaps == 0) return 0;
        double total = 0;
        for (int index = 0; index < gaps; index++) {
            Glyph glyph = glyph(codePoints[index], fonts);
            double natural;
            try {
                natural = glyph.font().getStringWidth(glyph.value()) * fontSize / 1000d;
            } catch (IOException | IllegalArgumentException ignored) {
                natural = fontSize;
            }
            double desired = points(block.advancesMm().get(index)) / horizontalRatio;
            total += desired - natural;
        }
        double average = total / gaps;
        return (float) Math.max(-fontSize * 0.8d, Math.min(fontSize * 3d, average));
    }

    private void writeFontRuns(PDPageContentStream content, String value,
                               float fontSize, FontSet fonts) throws IOException {
        PDFont active = null;
        StringBuilder run = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Glyph glyph = glyph(codePoint, fonts);
            if (active != glyph.font()) {
                showRun(content, active, run, fontSize);
                active = glyph.font();
            }
            run.append(glyph.value());
        }
        showRun(content, active, run, fontSize);
    }

    private void showRun(PDPageContentStream content, PDFont font,
                         StringBuilder run, float fontSize) throws IOException {
        if (font == null || run.isEmpty()) return;
        content.setFont(font, fontSize);
        content.showText(run.toString());
        run.setLength(0);
    }

    private Glyph glyph(int codePoint, FontSet fonts) {
        String value = new String(Character.toChars(codePoint));
        if (canEncode(fonts.latin(), value)) return new Glyph(fonts.latin(), value);
        if (canEncode(fonts.cjk(), value)) return new Glyph(fonts.cjk(), value);
        return new Glyph(fonts.latin(), "?");
    }

    private boolean canEncode(PDFont font, String value) {
        try {
            font.encode(value);
            return true;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private PDType0Font loadFont(PDDocument document, String resource) throws IOException {
        try (InputStream input = FixedLayoutPdfRenderer.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("内置 PDF 字体缺失：" + resource);
            return PDType0Font.load(document, input);
        }
    }

    private float x(PageModel page, double valueMm) {
        return points(valueMm - page.physicalBox().x());
    }

    private float y(PageModel page, double valueMm) {
        return points(page.physicalBox().height() - (valueMm - page.physicalBox().y()));
    }

    private static float points(double millimetres) {
        return (float) (millimetres * POINTS_PER_MM);
    }

    private static Color color(ColorValue value) {
        return new Color(value.red(), value.green(), value.blue(), value.alpha());
    }

    private record FontSet(PDFont latin, PDFont cjk) { }
    private record Glyph(PDFont font, String value) { }

    private record RenderItem(int zOrder, int kind, LineElement line, ImageBlock image, TextBlock text) {
        private static RenderItem line(LineElement value) {
            return new RenderItem(value.zOrder(), 0, value, null, null);
        }
        private static RenderItem image(ImageBlock value) {
            return new RenderItem(value.zOrder(), 1, null, value, null);
        }
        private static RenderItem text(TextBlock value) {
            return new RenderItem(value.zOrder(), 2, null, null, value);
        }
    }
}
