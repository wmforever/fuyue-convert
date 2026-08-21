package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.ImageBlock;
import com.fuyue.formatconverter.model.LineElement;
import com.fuyue.formatconverter.model.Point;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.Transform2D;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/** Extracts editable PDF text objects into the shared millimetre-based layout model. */
public final class PdfLayoutParser {
    private static final double MM_PER_POINT = 25.4d / 72d;
    /** Microsoft Word limits both page dimensions to 22 inches. */
    private static final double MAX_WORD_PAGE_POINTS = 22d * 72d;

    public DocumentModel parse(Path source, String displayName, ParseLimits limits) throws IOException {
        return parse(source, displayName, limits, ParseMode.EDITABLE_WORD);
    }

    /** Parses page geometry and text without applying editable-Word OCR/page-size contracts. */
    public DocumentModel parseForFixedLayout(Path source, String displayName, ParseLimits limits) throws IOException {
        return parse(source, displayName, limits, ParseMode.FIXED_LAYOUT);
    }

    /** Parses text for extraction without applying Word's 22-inch page-size limit. */
    public DocumentModel parseForTextExtraction(Path source, String displayName, ParseLimits limits) throws IOException {
        return parse(source, displayName, limits, ParseMode.TEXT_EXTRACTION);
    }

    /** Parses editable page geometry while allowing an explicitly configured OCR engine to fill scanned pages. */
    public DocumentModel parseForEditableOcr(Path source, String displayName, ParseLimits limits) throws IOException {
        return parse(source, displayName, limits, ParseMode.EDITABLE_OCR);
    }

    private DocumentModel parse(Path source, String displayName, ParseLimits limits,
                                ParseMode mode) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) throw new IOException("PDF 没有可转换页面");
            if (pageCount > limits.maxPages()) {
                throw new IOException("PDF 页数超过限制：" + pageCount + " > " + limits.maxPages());
            }

            List<PageState> states = new ArrayList<>(pageCount);
            for (int index = 0; index < pageCount; index++) {
                PDPage page = document.getPage(index);
                PageState state = pageState(page, index + 1, mode.enforceWordPageLimit());
                states.add(state);
            }

            LayoutTextStripper stripper = new LayoutTextStripper(states, limits.maxEntries());
            stripper.setSortByPosition(true);
            stripper.setShouldSeparateByBeads(false);
            stripper.setSuppressDuplicateOverlappingText(true);
            stripper.getText(document);

            Map<Integer, PageGraphics> graphics = extractGraphics(document, states, limits.maxEntries());

            List<PageModel> pages = new ArrayList<>(pageCount);
            for (PageState state : states) {
                if (mode.requiresExtractableText() && !state.hasEditableText() && state.hasVisibleContent()) {
                    throw new ConversionFailureException("OCR_REQUIRED",
                            "PDF 第 " + state.pageNumber() + " 页未检测到可编辑文字，可能是扫描件或纯图片 PDF；请先接入 OCR。");
                }
                List<ConversionWarning> warnings = new ArrayList<>();
                PageGraphics pageGraphics = graphics.getOrDefault(state.pageNumber(), PageGraphics.EMPTY);
                if (state.scale() < 0.9999d) {
                    warnings.add(ConversionWarning.of(WarningCode.OFFICE_COMPATIBILITY_LAYOUT,
                            "PDF 第 " + state.pageNumber() + " 页尺寸超过 Word 22 英寸上限，已按 "
                                    + String.format(Locale.ROOT, "%.1f", state.scale() * 100d)
                                    + "% 等比缩小页面、文字与布局。", state.pageNumber()));
                }
                if (mode.enforceWordPageLimit() && state.hasEditableText() && state.hasImageObjects()
                        && pageGraphics.images().isEmpty()) {
                    warnings.add(ConversionWarning.of(WarningCode.IMAGE_EXTRACTION_FAILED,
                            "PDF 第 " + state.pageNumber() + " 页包含图片；当前可编辑路线仅恢复文字，图片尚未写入 Word。",
                            state.pageNumber()));
                }
                pages.add(new PageModel(state.pageNumber(), state.pageBox(), state.texts(), pageGraphics.lines(), pageGraphics.images(),
                        List.of(), List.of(), warnings));
            }
            return new DocumentModel(displayName, "PDFBox 3.0.8", pageCount, pages, List.of());
        } catch (InvalidPasswordException e) {
            throw new ConversionFailureException("PDF_PASSWORD_REQUIRED",
                    "PDF 已加密，需要密码；当前任务 API 不接收密码。");
        }
    }

    private PageState pageState(PDPage page, int pageNumber, boolean enforceWordPageLimit) throws IOException {
        PDRectangle crop = page.getCropBox();
        double userUnit = positive(page.getUserUnit(), 1d);
        int rotation = Math.floorMod(page.getRotation(), 360);
        boolean sideways = rotation == 90 || rotation == 270;
        double widthPoints = (sideways ? crop.getHeight() : crop.getWidth()) * userUnit;
        double heightPoints = (sideways ? crop.getWidth() : crop.getHeight()) * userUnit;
        if (!Double.isFinite(widthPoints) || !Double.isFinite(heightPoints)
                || widthPoints <= 0 || heightPoints <= 0) {
            throw new IOException("PDF 第 " + pageNumber + " 页尺寸无效");
        }
        double scale = enforceWordPageLimit
                ? Math.min(1d, Math.min(MAX_WORD_PAGE_POINTS / widthPoints, MAX_WORD_PAGE_POINTS / heightPoints))
                : 1d;
        double scaledUnit = userUnit * scale;
        return new PageState(pageNumber,
                new Rect(0, 0, pointsToMm(widthPoints * scale), pointsToMm(heightPoints * scale)), scaledUnit, rotation,
                hasVisibleContent(page), hasImageObjects(page.getResources(),
                        java.util.Collections.newSetFromMap(new IdentityHashMap<>())), new ArrayList<>(), scale);
    }

    private boolean hasVisibleContent(PDPage page) throws IOException {
        if (!page.hasContents()) return !page.getAnnotations().isEmpty();
        try (InputStream input = page.getContents()) {
            int value;
            while ((value = input.read()) >= 0) {
                if (!Character.isWhitespace(value)) return true;
            }
        }
        return !page.getAnnotations().isEmpty();
    }

    private boolean hasImageObjects(PDResources resources, Set<Object> visited) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) return false;
        for (var name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject) return true;
            if (object instanceof PDFormXObject form && hasImageObjects(form.getResources(), visited)) return true;
        }
        return false;
    }

    private Map<Integer, PageGraphics> extractGraphics(PDDocument document, List<PageState> states, int maxEntries) {
        Map<Integer, PageGraphics> result = new java.util.HashMap<>();
        for (int index = 0; index < states.size(); index++) {
            PageState state = states.get(index);
            try {
                PdfGraphicsCollector collector = new PdfGraphicsCollector(document.getPage(index), state, maxEntries);
                collector.processPage(document.getPage(index));
                result.put(state.pageNumber(), collector.graphics());
            } catch (Exception ignored) {
                result.put(state.pageNumber(), PageGraphics.EMPTY);
            }
        }
        return result;
    }

    private static double pointsToMm(double points) { return points * MM_PER_POINT; }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private enum ParseMode {
        EDITABLE_WORD(true, true),
        EDITABLE_OCR(false, true),
        TEXT_EXTRACTION(true, false),
        FIXED_LAYOUT(false, false);

        private final boolean requiresExtractableText;
        private final boolean enforceWordPageLimit;

        ParseMode(boolean requiresExtractableText, boolean enforceWordPageLimit) {
            this.requiresExtractableText = requiresExtractableText;
            this.enforceWordPageLimit = enforceWordPageLimit;
        }

        boolean requiresExtractableText() { return requiresExtractableText; }
        boolean enforceWordPageLimit() { return enforceWordPageLimit; }
    }

    private static final class LayoutTextStripper extends PDFTextStripper {
        private final List<PageState> pages;
        private final int maxTextObjects;
        private final Map<TextPosition, ColorValue> colors = new IdentityHashMap<>();
        private PageState current;
        private int textObjects;

        private LayoutTextStripper(List<PageState> pages, int maxTextObjects) {
            this.pages = pages;
            this.maxTextObjects = Math.max(1, maxTextObjects);
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            int index = getCurrentPageNo() - 1;
            if (index < 0 || index >= pages.size()) throw new IOException("PDF 页面索引不一致");
            current = pages.get(index);
            super.startPage(page);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            try {
                int rgb = getGraphicsState().getNonStrokingColor().toRGB();
                colors.put(text, new ColorValue((rgb >>> 16) & 0xff, (rgb >>> 8) & 0xff, rgb & 0xff, 255));
            } catch (IOException | RuntimeException ignored) {
                colors.put(text, ColorValue.BLACK);
            }
            super.processTextPosition(text);
        }

        @Override
        protected void writeString(String ignored, List<TextPosition> positions) throws IOException {
            if (current == null || positions == null || positions.isEmpty()) return;
            List<TextPosition> run = new ArrayList<>();
            RunKey key = null;
            for (TextPosition position : positions) {
                if (position == null) continue;
                ColorValue color = colors.remove(position);
                if (position.getUnicode() == null || position.getUnicode().isEmpty()) continue;
                RunKey next = RunKey.from(position, color == null ? ColorValue.BLACK : color);
                if (key != null && !key.compatible(next)) {
                    addRun(run, key);
                    run.clear();
                }
                key = next;
                run.add(position);
            }
            if (key != null && !run.isEmpty()) addRun(run, key);
        }

        private void addRun(List<TextPosition> positions, RunKey key) throws IOException {
            if (++textObjects > maxTextObjects) {
                throw new IOException("PDF 文字对象数量超过限制：" + textObjects + " > " + maxTextObjects);
            }
            String text = positions.stream().map(TextPosition::getUnicode).reduce("", String::concat);
            if (text.isEmpty()) return;

            double unit = current.userUnit();
            double displayedDirection = normalizeDegrees(current.rotation() - key.direction());
            double radians = Math.toRadians(displayedDirection);
            double advanceX = Math.cos(radians);
            double advanceY = Math.sin(radians);
            double glyphTopX = Math.sin(radians);
            double glyphTopY = -Math.cos(radians);
            double minAdvance = Double.POSITIVE_INFINITY;
            double minCross = Double.POSITIVE_INFINITY;
            double maxAdvance = Double.NEGATIVE_INFINITY;
            double maxCross = Double.NEGATIVE_INFINITY;
            double baseline = 0;
            for (TextPosition position : positions) {
                // getX()/getY() are adjusted for the PDF page /Rotate value. The DirAdj variants
                // instead rotate every glyph into its own reading direction and therefore lose
                // the displayed page coordinate system needed by fixed-position Word shapes.
                double x = position.getX() * unit;
                double y = position.getY() * unit;
                double width = Math.max(0.01d, position.getWidthDirAdj() * unit);
                double height = Math.max(0.01d, position.getHeightDir() * unit);
                double along = x * advanceX + y * advanceY;
                double cross = x * glyphTopX + y * glyphTopY;
                minAdvance = Math.min(minAdvance, along);
                minCross = Math.min(minCross, cross);
                maxAdvance = Math.max(maxAdvance, along + width);
                maxCross = Math.max(maxCross, cross + height);
                baseline += y;
            }
            baseline /= positions.size();
            double widthPoints = Math.max(0.01d, maxAdvance - minAdvance);
            double heightPoints = Math.max(0.01d, maxCross - minCross);
            double centerAdvance = (minAdvance + maxAdvance) / 2d;
            double centerCross = (minCross + maxCross) / 2d;
            double centerX = advanceX * centerAdvance + glyphTopX * centerCross;
            double centerY = advanceY * centerAdvance + glyphTopY * centerCross;
            Rect box = new Rect(pointsToMm(centerX - widthPoints / 2d),
                    pointsToMm(centerY - heightPoints / 2d),
                    pointsToMm(widthPoints), pointsToMm(heightPoints));
            double fontSize = positive(key.fontSizePt() * unit, 10.5d);
            FontStyle style = new FontStyle(key.family(), fontSize, key.bold(), key.italic(), key.color());
            current.texts().add(new TextBlock("pdf-p" + current.pageNumber() + "-t" + textObjects,
                    current.pageNumber(), box, text, pointsToMm(baseline), style, textObjects,
                    0, 0, List.of(), rotation(displayedDirection)));
        }

        private static double normalizeDegrees(double degrees) {
            double normalized = degrees % 360d;
            return normalized < 0 ? normalized + 360d : normalized;
        }

        private static Transform2D rotation(double degrees) {
            double normalized = normalizeDegrees(degrees);
            if (Math.abs(normalized) < 0.001d || Math.abs(normalized - 360d) < 0.001d) {
                return Transform2D.IDENTITY;
            }
            double radians = Math.toRadians(normalized);
            return new Transform2D(Math.cos(radians), Math.sin(radians),
                    -Math.sin(radians), Math.cos(radians), 0, 0);
        }
    }

    private record RunKey(String family, double fontSizePt, boolean bold, boolean italic,
                          double direction, ColorValue color) {
        private static RunKey from(TextPosition position, ColorValue color) {
            PDFont font = position.getFont();
            PDFontDescriptor descriptor = font == null ? null : font.getFontDescriptor();
            String rawName = descriptor == null ? null : descriptor.getFontFamily();
            if (rawName == null || rawName.isBlank()) rawName = font == null ? null : font.getName();
            String family = normalizeFontName(rawName);
            String lower = family.toLowerCase(Locale.ROOT);
            boolean bold = lower.contains("bold") || lower.contains("black") || lower.contains("heavy")
                    || descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600);
            boolean italic = lower.contains("italic") || lower.contains("oblique")
                    || descriptor != null && (descriptor.isItalic() || Math.abs(descriptor.getItalicAngle()) > 0.1f);
            return new RunKey(family, positive(position.getFontSizeInPt(), 10.5d), bold, italic,
                    position.getDir(), color);
        }

        private boolean compatible(RunKey other) {
            return family.equals(other.family)
                    && Math.abs(fontSizePt - other.fontSizePt) < 0.1d
                    && bold == other.bold && italic == other.italic
                    && Math.abs(direction - other.direction) < 0.1d
                    && color.equals(other.color);
        }

        private static String normalizeFontName(String value) {
            if (value == null || value.isBlank()) return "SimSun";
            String name = value.replaceFirst("^[A-Z]{6}\\+", "").replace(',', ' ').trim();
            return name.isBlank() ? "SimSun" : name;
        }
    }

    private record PageState(int pageNumber, Rect pageBox, double userUnit, int rotation,
                             boolean hasVisibleContent, boolean hasImageObjects,
                             List<TextBlock> texts, double scale) {
        private boolean hasEditableText() {
            return texts.stream().anyMatch(text -> !text.text().isBlank());
        }
    }

    private record PageGraphics(List<LineElement> lines, List<ImageBlock> images) {
        private static final PageGraphics EMPTY = new PageGraphics(List.of(), List.of());
        private PageGraphics {
            lines = List.copyOf(lines);
            images = List.copyOf(images);
        }
    }

    /** Extracts simple vector rules and image placements used by editable Word tables and pictures. */
    private static final class PdfGraphicsCollector extends PDFGraphicsStreamEngine {
        private final PageState page;
        private final int maxEntries;
        private final Path2D.Float path = new Path2D.Float();
        private final List<LineElement> lines = new ArrayList<>();
        private final List<ImageBlock> images = new ArrayList<>();
        private int entries;

        private PdfGraphicsCollector(PDPage source, PageState page, int maxEntries) {
            super(source);
            this.page = page;
            this.maxEntries = Math.max(1, maxEntries);
        }

        private PageGraphics graphics() { return new PageGraphics(lines, images); }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            path.moveTo(p0.getX(), p0.getY()); path.lineTo(p1.getX(), p1.getY());
            path.lineTo(p2.getX(), p2.getY()); path.lineTo(p3.getX(), p3.getY()); path.closePath();
        }

        @Override public void drawImage(PDImage image) throws IOException {
            if (++entries > maxEntries) throw new IOException("PDF 图形对象数量超过限制");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            if (!ImageIO.write(image.getImage(), "png", data)) return;
            Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
            float width = Math.abs(matrix.getScalingFactorX());
            float height = Math.abs(matrix.getScalingFactorY());
            float x = matrix.getTranslateX();
            float y = matrix.getTranslateY();
            Rect box = rect(x, y, width, height);
            if (box.width() > 0.1d && box.height() > 0.1d) {
                images.add(new ImageBlock("pdf-p%d-image-%d".formatted(page.pageNumber(), entries), page.pageNumber(), box,
                        "image/png", data.toByteArray(), "PDF_IMAGE", -100 + entries));
            }
        }

        @Override public void clip(int windingRule) { path.setWindingRule(windingRule); }
        @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
        @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { path.curveTo(x1, y1, x2, y2, x3, y3); }
        @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
        @Override public void closePath() { path.closePath(); }
        @Override public void endPath() { path.reset(); }
        @Override public void strokePath() throws IOException { addPathLines(); path.reset(); }
        @Override public void fillPath(int windingRule) { path.reset(); }
        @Override public void fillAndStrokePath(int windingRule) throws IOException { addPathLines(); path.reset(); }
        @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }

        private void addPathLines() throws IOException {
            PathIterator iterator = path.getPathIterator(null);
            double[] coords = new double[6];
            Point2D.Double previous = null;
            while (!iterator.isDone()) {
                int type = iterator.currentSegment(coords);
                if (type == PathIterator.SEG_MOVETO) previous = new Point2D.Double(coords[0], coords[1]);
                else if (type == PathIterator.SEG_LINETO && previous != null) {
                    addLine(previous, new Point2D.Double(coords[0], coords[1]));
                    previous = new Point2D.Double(coords[0], coords[1]);
                }
                iterator.next();
            }
        }

        private void addLine(Point2D from, Point2D to) throws IOException {
            if (++entries > maxEntries) throw new IOException("PDF 图形对象数量超过限制");
            Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
            Point2D start = matrix.transformPoint((float) from.getX(), (float) from.getY());
            Point2D end = matrix.transformPoint((float) to.getX(), (float) to.getY());
            Point a = point(start.getX(), start.getY());
            Point b = point(end.getX(), end.getY());
            if (Math.hypot(a.x() - b.x(), a.y() - b.y()) < 0.5d) return;
            int rgb;
            try { rgb = getGraphicsState().getStrokingColor().toRGB(); } catch (Exception ignored) { rgb = 0; }
            lines.add(new LineElement("pdf-p%d-line-%d".formatted(page.pageNumber(), entries), page.pageNumber(), a, b,
                    Math.max(0.1d, getGraphicsState().getLineWidth() * page.userUnit() * MM_PER_POINT),
                    new ColorValue((rgb >>> 16) & 0xff, (rgb >>> 8) & 0xff, rgb & 0xff, 255), entries));
        }

        private Rect rect(double x, double y, double width, double height) {
            double unit = page.userUnit();
            return new Rect(pointsToMm(x * unit), pointsToMm((page.pageBox().height() / MM_PER_POINT) - (y + height) * unit),
                    pointsToMm(width * unit), pointsToMm(height * unit));
        }

        private Point point(double x, double y) {
            double unit = page.userUnit();
            return new Point(pointsToMm(x * unit), page.pageBox().height() - pointsToMm(y * unit));
        }
    }
}
