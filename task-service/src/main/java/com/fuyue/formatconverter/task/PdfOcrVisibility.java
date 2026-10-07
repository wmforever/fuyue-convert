package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A bounded proof of later opaque covers, never an estimate of OCR completeness. */
final class PdfOcrVisibility extends PDFGraphicsStreamEngine {
    private static final double MM_PER_POINT = 25.4d / 72d;
    private final GeneralPath path = new GeneralPath();
    private final Area opaque = new Area();
    private final Area uncertain = new Area();
    private Area imageClip;
    private final ImageBlock expectedImage;
    private final int maxOperators;
    private final long deadline = System.nanoTime() + 250_000_000L;
    private int operators, covers, images;
    private int pendingClip = -1;
    private boolean unsupported;
    private boolean imageMatches;

    private PdfOcrVisibility(PDPage page, ImageBlock image, int maxEntries) {
        super(page);
        expectedImage = image;
        maxOperators = Math.min(20_000, Math.max(1, maxEntries));
    }

    static PdfOcrVisibility inspect(PDPage page, List<ImageBlock> images, int maxEntries) throws IOException {
        // The parser preserves the full axis-aligned image box, including slight
        // Office overflow beyond the page. Prove clipping per word, not by
        // requiring the entire scan (including blank margins) to fit the page.
        if (images.size() != 1 || page.getRotation() != 0 || page.getUserUnit() != 1f
                || page.getCropBox().getLowerLeftX() != 0f || page.getCropBox().getLowerLeftY() != 0f) return null;
        PdfOcrVisibility result = new PdfOcrVisibility(page, images.get(0), maxEntries);
        result.processPage(page);
        return result.images == 1 && result.imageMatches ? result : null;
    }

    record Filtered(List<TextBlock> blocks, int hiddenWords) { }

    boolean unobscuredRules(OcrRuledGrid.Grid grid) {
        return !unsupported && uncertain.isEmpty() && grid.rules().stream()
                .allMatch(rule -> imageClip.contains(pdfBox(rule)) && !touches(rule));
    }

    Filtered filter(List<TextBlock> blocks) throws ConversionFailureException {
        if (opaque.isEmpty() && uncertain.isEmpty()) return new Filtered(blocks, 0);
        List<TextBlock> visible = new ArrayList<>();
        int hiddenWords = 0;
        for (TextBlock block : blocks) {
            if (block.ocrWords().isEmpty()) {
                if (touches(block.box())) throw ambiguous();
                visible.add(block);
                continue;
            }
            int hidden = 0;
            for (TextBlock.OcrWord word : block.ocrWords()) {
                Rectangle2D box = pdfBox(word.box());
                if (!imageClip.contains(box)) throw ambiguous();
                if (!opaque.intersects(box) && !uncertain.intersects(box)) continue;
                if (unsupported || uncertain.intersects(box) || !opaque.contains(box)) throw ambiguous();
                hidden++;
            }
            // Keep source line/word boundaries intact. Partly hidden lines need
            // a separate visible-region recognition path; never splice numbers.
            if (hidden != 0 && hidden != block.ocrWords().size()) throw ambiguous();
            if (hidden == 0) visible.add(block);
            hiddenWords += hidden;
        }
        return new Filtered(List.copyOf(visible), hiddenWords);
    }

    private boolean touches(Rect box) { return opaque.intersects(pdfBox(box)) || uncertain.intersects(pdfBox(box)); }
    private Rectangle2D pdfBox(Rect box) {
        return new Rectangle2D.Double(box.x() / MM_PER_POINT,
                getPage().getCropBox().getHeight() - box.bottom() / MM_PER_POINT,
                box.width() / MM_PER_POINT, box.height() / MM_PER_POINT);
    }
    private ConversionFailureException ambiguous() {
        return new ConversionFailureException("OCR_VISIBILITY_UNCERTAIN",
                "PDF 扫描文字与后绘制的遮罩部分重叠或透明度/层级不明确，拒绝合并可能已被遮挡的旧文字。");
    }

    @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (++operators > maxOperators || System.nanoTime() > deadline) {
            throw new IOException("PDF OCR visibility analysis limit exceeded");
        }
        if ("BDC".equals(operator.getName()) && !operands.isEmpty() && COSName.OC.equals(operands.get(0))) unsupported = true;
        super.processOperator(operator, operands);
    }

    @Override public void drawImage(PDImage image) {
        images++;
        Matrix m = getGraphicsState().getCurrentTransformationMatrix();
        if (images != 1 || m.getShearX() != 0 || m.getShearY() != 0 || m.getScaleX() <= 0 || m.getScaleY() <= 0) return;
        Rectangle2D bounds = new Rectangle2D.Double(m.getTranslateX(), m.getTranslateY(), m.getScaleX(), m.getScaleY());
        Rectangle2D expected = pdfBox(expectedImage.box());
        imageClip = new Area(getGraphicsState().getCurrentClippingPath());
        imageMatches = close(bounds.getX(), expected.getX()) && close(bounds.getY(), expected.getY())
                && close(bounds.getWidth(), expected.getWidth()) && close(bounds.getHeight(), expected.getHeight())
                && image.getCOSObject().getDictionaryObject(COSName.OC) == null;
    }
    private static boolean close(double a, double b) { return Math.abs(a - b) < 0.00001d; }

    @Override public void showForm(PDFormXObject form) { unsupported = true; }
    @Override public void showTransparencyGroup(PDTransparencyGroup group) { unsupported = true; }
    @Override protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) {
        var mode = getGraphicsState().getTextState().getRenderingMode();
        if (font instanceof PDType3Font || mode.isClip() || (!mode.isFill() && !mode.isStroke())
                || getGraphicsState().getSoftMask() != null || getGraphicsState().getBlendMode() != BlendMode.NORMAL
                || (mode.isFill() && getGraphicsState().getNonStrokeAlphaConstant() != 1d)
                || (mode.isStroke() && getGraphicsState().getAlphaConstant() != 1d)) unsupported = true;
    }
    @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
        path.moveTo(a.getX(), a.getY()); path.lineTo(b.getX(), b.getY());
        path.lineTo(c.getX(), c.getY()); path.lineTo(d.getX(), d.getY()); path.closePath();
    }
    @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
    @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { path.curveTo(x1, y1, x2, y2, x3, y3); }
    @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
    @Override public void closePath() { path.closePath(); }
    @Override public void clip(int rule) { pendingClip = rule; }
    @Override public void endPath() { finishPath(); }
    @Override public void strokePath() { finishPath(); }
    @Override public void fillPath(int rule) throws IOException { cover(rule); finishPath(); }
    @Override public void fillAndStrokePath(int rule) throws IOException { cover(rule); finishPath(); }
    @Override public void shadingFill(COSName name) { unsupported = true; }

    private void cover(int rule) throws IOException {
        if (images == 0) return; // A cover behind the image proves nothing.
        if (++covers > 512) throw new IOException("PDF OCR cover count limit exceeded");
        path.setWindingRule(rule);
        Area area = new Area(path);
        boolean rectangle = area.isRectangular();
        area.intersect(getGraphicsState().getCurrentClippingPath());
        var state = getGraphicsState();
        var color = state.getNonStrokingColor().getColorSpace();
        boolean solid = rectangle && state.getNonStrokeAlphaConstant() == 1d && state.getSoftMask() == null
                && state.getBlendMode() == BlendMode.NORMAL && !state.isNonStrokingOverprint()
                && (color instanceof PDDeviceGray || color instanceof PDDeviceRGB);
        if (solid) opaque.add(area); else uncertain.add(area);
    }
    private void finishPath() {
        if (pendingClip != -1) {
            path.setWindingRule(pendingClip);
            getGraphicsState().intersectClippingPath(new Area(path));
            pendingClip = -1;
        }
        path.reset();
    }
}
