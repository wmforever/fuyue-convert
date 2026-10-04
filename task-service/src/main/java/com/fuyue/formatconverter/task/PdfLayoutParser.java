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
import com.fuyue.formatconverter.model.ScannedContentDetector;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.Transform2D;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence;
import org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties;
import org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
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

    /** Parses extraction geometry while allowing OCR without applying Word's page-size limit. */
    public DocumentModel parseForTextExtractionOcr(Path source, String displayName, ParseLimits limits)
            throws IOException {
        return parse(source, displayName, limits, ParseMode.TEXT_EXTRACTION_OCR);
    }

    DocumentModel parseForTextSections(Path source, String name, ParseLimits limits,
                                       boolean acceptsOcr, PdfTextSectionOrder sections) throws IOException {
        return parse(source, name, limits, acceptsOcr ? ParseMode.TEXT_EXTRACTION_OCR : ParseMode.TEXT_EXTRACTION, sections);
    }

    private DocumentModel parse(Path source, String displayName, ParseLimits limits,
                                ParseMode mode) throws IOException {
        return parse(source, displayName, limits, mode, null);
    }

    private DocumentModel parse(Path source, String displayName, ParseLimits limits,
                                ParseMode mode, PdfTextSectionOrder sections) throws IOException {
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

            if (sections != null) sections.initialize(document, limits.maxEntries());
            LayoutTextStripper stripper = new LayoutTextStripper(states, limits.maxEntries(), sections);
            stripper.setSortByPosition(true);
            stripper.setShouldSeparateByBeads(false);
            stripper.setSuppressDuplicateOverlappingText(true);
            stripper.getText(document);

            Map<Integer, PageGraphics> graphics = extractGraphics(document, states, limits.maxEntries());

            List<PageModel> pages = new ArrayList<>(pageCount);
            for (PageState state : states) {
                List<ConversionWarning> warnings = new ArrayList<>();
                PageGraphics pageGraphics = graphics.getOrDefault(state.pageNumber(), PageGraphics.EMPTY);
                if (pageGraphics.imageExtractionFailed()
                        && (mode.requiresExtractableText() || mode.acceptsOcr())) {
                    throw new ConversionFailureException("PDF_IMAGE_EXTRACTION_FAILED",
                            "PDF 第 " + state.pageNumber()
                                    + " 页包含无法安全提取的图片，拒绝生成可能缺失内容的结果。");
                }
                boolean imageRequiresOcr;
                try {
                    imageRequiresOcr = ScannedContentDetector.requiresOcr(
                            state.texts(), pageGraphics.images(), state.pageBox());
                } catch (ScannedContentDetector.AnalysisLimitException e) {
                    throw new ConversionFailureException("OCR_IMAGE_LIMIT_EXCEEDED",
                            "PDF 第 " + state.pageNumber() + " 页图片候选过多，拒绝不完整转换。");
                }
                boolean requiresOcr = !state.hasEditableText() && state.hasVisibleContent()
                        || imageRequiresOcr;
                if (mode.requiresExtractableText() && requiresOcr) {
                    throw new ConversionFailureException("OCR_REQUIRED",
                            "PDF 第 " + state.pageNumber()
                                    + " 页包含缺少可编辑文字层的扫描图像内容；请先接入 OCR。");
                }
                if (mode.acceptsOcr() && requiresOcr) {
                    warnings.add(ConversionWarning.of(WarningCode.OCR_REQUIRED,
                            "PDF 第 " + state.pageNumber()
                                    + " 页包含缺少可编辑文字层的扫描图像内容，需要 OCR。",
                            state.pageNumber()));
                }
                if (state.scale() < 0.9999d) {
                    warnings.add(ConversionWarning.of(WarningCode.OFFICE_COMPATIBILITY_LAYOUT,
                            "PDF 第 " + state.pageNumber() + " 页尺寸超过 Word 22 英寸上限，已按 "
                                    + String.format(Locale.ROOT, "%.1f", state.scale() * 100d)
                                    + "% 等比缩小页面、文字与布局。", state.pageNumber()));
                }
                if (pageGraphics.imageExtractionFailed()) {
                    warnings.add(ConversionWarning.of(WarningCode.IMAGE_EXTRACTION_FAILED,
                            "PDF 第 " + state.pageNumber() + " 页有实际绘制的图片无法提取，请复核输出。",
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
                hasVisibleContent(page), new ArrayList<>(), scale);
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

    private Map<Integer, PageGraphics> extractGraphics(PDDocument document, List<PageState> states, int maxEntries) {
        Map<Integer, PageGraphics> result = new java.util.HashMap<>();
        for (int index = 0; index < states.size(); index++) {
            PageState state = states.get(index);
            PdfGraphicsCollector collector = new PdfGraphicsCollector(document.getPage(index), state, maxEntries);
            try {
                collector.processPage(document.getPage(index));
            } catch (Exception ignored) {
                // Keep the successfully collected objects and the explicit image
                // decode-failure flag. Unrelated vector parsing failures must not be
                // mistaken for an unreferenced image resource.
            }
            result.put(state.pageNumber(), collector.graphics());
        }
        return result;
    }

    private static double pointsToMm(double points) { return points * MM_PER_POINT; }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private enum ParseMode {
        EDITABLE_WORD(true, true, false),
        EDITABLE_OCR(false, true, true),
        TEXT_EXTRACTION(true, false, false),
        TEXT_EXTRACTION_OCR(false, false, true),
        FIXED_LAYOUT(false, false, false);

        private final boolean requiresExtractableText;
        private final boolean enforceWordPageLimit;
        private final boolean acceptsOcr;

        ParseMode(boolean requiresExtractableText, boolean enforceWordPageLimit, boolean acceptsOcr) {
            this.requiresExtractableText = requiresExtractableText;
            this.enforceWordPageLimit = enforceWordPageLimit;
            this.acceptsOcr = acceptsOcr;
        }

        boolean requiresExtractableText() { return requiresExtractableText; }
        boolean enforceWordPageLimit() { return enforceWordPageLimit; }
        boolean acceptsOcr() { return acceptsOcr; }
    }

    private static final class LayoutTextStripper extends PDFTextStripper {
        private final List<PageState> pages;
        private final int maxTextObjects;
        private final Map<TextPosition, ColorValue> colors = new IdentityHashMap<>();
        private final Map<PDFont, PdfFontNames.Face> fontFaces = new IdentityHashMap<>();
        private PageState current;
        private int textObjects;
        private final PdfTextSectionOrder sections;
        private final Map<TextPosition, Integer> positionGroups = new IdentityHashMap<>();
        private final java.util.Deque<Integer> marked = new java.util.ArrayDeque<>();
        private int markedDepth;

        private LayoutTextStripper(List<PageState> pages, int maxTextObjects, PdfTextSectionOrder sections) {
            this.pages = pages;
            this.maxTextObjects = Math.max(1, maxTextObjects);
            this.sections = sections != null && sections.enabled() ? sections : null;
            if (this.sections != null) {
                addOperator(new BeginMarkedContentSequence(this));
                addOperator(new BeginMarkedContentSequenceWithProperties(this));
                addOperator(new EndMarkedContentSequence(this));
            }
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            int index = getCurrentPageNo() - 1;
            if (index < 0 || index >= pages.size()) throw new IOException("PDF 页面索引不一致");
            current = pages.get(index);
            positionGroups.clear();marked.clear();markedDepth = 0;
            super.startPage(page);
        }

        @Override
        public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
            if (sections == null) return;
            if (++markedDepth > 64) { sections.disablePage(current.pageNumber()); return; }
            int group;
            if (properties != null && properties.containsKey(COSName.MCID)) {
                var id = properties.getDictionaryObject(COSName.MCID);
                group = id instanceof COSInteger number && number.longValue() >= 0
                        && number.longValue() <= Integer.MAX_VALUE
                        ? sections.group(current.pageNumber(), number.intValue()) : -1;
            } else group = marked.isEmpty() ? -1 : marked.peek();
            marked.push("Artifact".equals(tag.getName()) ? -1 : group);
        }

        @Override
        public void endMarkedContentSequence() {
            if (sections == null) return;
            if (markedDepth <= 0) { sections.disablePage(current.pageNumber()); return; }
            if (markedDepth-- <= 64) marked.pop();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            if (sections != null && markedDepth != 0) sections.disablePage(current.pageNumber());
            super.endPage(page);
        }

        @Override
        public void showForm(org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) throws IOException {
            // Form MCIDs use another content namespace; retain the legacy path
            // until stream-scoped structure references are supported.
            if (sections != null) sections.disablePage(current.pageNumber());
            super.showForm(form);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (sections != null) positionGroups.put(text,
                    markedDepth > 64 || marked.isEmpty() ? -1 : marked.peek());
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
                PdfFontNames.Face face = fontFaces.computeIfAbsent(position.getFont(), PdfFontNames::from);
                RunKey next = RunKey.from(position, color == null ? ColorValue.BLACK : color, face);
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
            if (sections != null) {
                int group = positionGroups.getOrDefault(positions.get(0), -1);
                for (TextPosition position : positions) {
                    if (positionGroups.getOrDefault(position, -1) != group) group = -1;
                }
                for (TextPosition position : positions) positionGroups.remove(position);
                sections.record("pdf-p" + current.pageNumber() + "-t" + textObjects, group);
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
        private static RunKey from(TextPosition position, ColorValue color, PdfFontNames.Face face) {
            return new RunKey(face.family(), positive(position.getFontSizeInPt(), 10.5d), face.bold(), face.italic(),
                    position.getDir(), color);
        }

        private boolean compatible(RunKey other) {
            return family.equals(other.family)
                    && Math.abs(fontSizePt - other.fontSizePt) < 0.1d
                    && bold == other.bold && italic == other.italic
                    && Math.abs(direction - other.direction) < 0.1d
                    && color.equals(other.color);
        }

    }

    private record PageState(int pageNumber, Rect pageBox, double userUnit, int rotation,
                             boolean hasVisibleContent, List<TextBlock> texts, double scale) {
        private boolean hasEditableText() {
            return texts.stream().anyMatch(text -> !text.text().isBlank());
        }
    }

    private record PageGraphics(List<LineElement> lines, List<ImageBlock> images,
                                boolean imageExtractionFailed) {
        private static final PageGraphics EMPTY = new PageGraphics(List.of(), List.of(), false);
        private PageGraphics {
            lines = List.copyOf(lines);
            images = List.copyOf(images);
        }
    }

    /** Extracts simple vector rules and image placements used by editable Word tables and pictures. */
    private static final class PdfGraphicsCollector extends PDFGraphicsStreamEngine {
        private final PageState page;
        private final PDRectangle cropBox;
        private final int maxEntries;
        private final Path2D.Float path = new Path2D.Float();
        private final List<LineElement> lines = new ArrayList<>();
        private final List<ImageBlock> images = new ArrayList<>();
        private boolean imageExtractionFailed;
        private int entries;

        private PdfGraphicsCollector(PDPage source, PageState page, int maxEntries) {
            super(source);
            this.page = page;
            this.cropBox = source.getCropBox();
            this.maxEntries = Math.max(1, maxEntries);
        }

        private PageGraphics graphics() { return new PageGraphics(lines, images, imageExtractionFailed); }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            path.moveTo(p0.getX(), p0.getY()); path.lineTo(p1.getX(), p1.getY());
            path.lineTo(p2.getX(), p2.getY()); path.lineTo(p3.getX(), p3.getY()); path.closePath();
        }

        @Override public void drawImage(PDImage image) throws IOException {
            if (++entries > maxEntries) {
                imageExtractionFailed = true;
                throw new IOException("PDF 图形对象数量超过限制");
            }
            try {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                if (!ImageIO.write(image.getImage(), "png", data)) {
                    imageExtractionFailed = true;
                    return;
                }
                Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
                float width = Math.abs(matrix.getScalingFactorX());
                float height = Math.abs(matrix.getScalingFactorY());
                float x = matrix.getTranslateX();
                float y = matrix.getTranslateY();
                Rect box = rect(x, y, width, height);
                if (box.width() > 0.1d && box.height() > 0.1d) {
                    images.add(new ImageBlock("pdf-p%d-image-%d".formatted(page.pageNumber(), entries),
                            page.pageNumber(), box, "image/png", data.toByteArray(), "PDF_IMAGE", -100 + entries));
                }
            } catch (IOException | RuntimeException ignored) {
                imageExtractionFailed = true;
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
        @Override public void fillPath(int windingRule) throws IOException { addFilledRules(); path.reset(); }
        @Override public void fillAndStrokePath(int windingRule) throws IOException { addPathLines(); path.reset(); }
        @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }

        private void addPathLines() throws IOException {
            PathIterator iterator = path.getPathIterator(null);
            double[] coords = new double[6];
            Point2D.Double previous = null;
            Point2D.Double first = null;
            while (!iterator.isDone()) {
                int type = iterator.currentSegment(coords);
                if (type == PathIterator.SEG_MOVETO) {
                    first = new Point2D.Double(coords[0], coords[1]);
                    previous = first;
                }
                else if (type == PathIterator.SEG_LINETO && previous != null) {
                    addLine(previous, new Point2D.Double(coords[0], coords[1]));
                    previous = new Point2D.Double(coords[0], coords[1]);
                } else if (type == PathIterator.SEG_CLOSE && previous != null && first != null) {
                    addLine(previous, first);
                    previous = first;
                } else if (type == PathIterator.SEG_QUADTO) {
                    previous = new Point2D.Double(coords[2], coords[3]);
                } else if (type == PathIterator.SEG_CUBICTO) {
                    previous = new Point2D.Double(coords[4], coords[5]);
                }
                iterator.next();
            }
        }

        /** Filled hairline rectangles are common table borders in Office/browser PDFs. */
        private void addFilledRules() throws IOException {
            PathIterator iterator = path.getPathIterator(null);
            double[] coords = new double[6];
            List<Point> corners = new ArrayList<>();
            boolean curved = false;
            while (!iterator.isDone()) {
                int type = iterator.currentSegment(coords);
                if (type == PathIterator.SEG_MOVETO) {
                    if (!curved) addFilledRule(corners);
                    corners.clear();
                    curved = false;
                    corners.add(point(coords[0], coords[1]));
                } else if (type == PathIterator.SEG_LINETO) {
                    corners.add(point(coords[0], coords[1]));
                } else if (type == PathIterator.SEG_CLOSE) {
                    if (!curved) addFilledRule(corners);
                    corners.clear();
                } else {
                    curved = true;
                }
                iterator.next();
            }
            // PDF filling implicitly closes open subpaths too.
            if (!curved) addFilledRule(corners);
        }

        private void addFilledRule(List<Point> corners) throws IOException {
            if (corners.size() == 5 && distance(corners.get(0), corners.get(4)) < 0.001d) {
                corners = corners.subList(0, 4);
            }
            if (corners.size() != 4) return;
            for (int i = 0; i < 4; i++) {
                Point from = corners.get(i), to = corners.get((i + 1) % 4);
                // Preserve only axis-aligned rectangles, never arbitrary filled artwork.
                if (Math.abs(from.x() - to.x()) > 0.001d && Math.abs(from.y() - to.y()) > 0.001d) return;
            }
            double minX = corners.stream().mapToDouble(Point::x).min().orElseThrow();
            double maxX = corners.stream().mapToDouble(Point::x).max().orElseThrow();
            double minY = corners.stream().mapToDouble(Point::y).min().orElseThrow();
            double maxY = corners.stream().mapToDouble(Point::y).max().orElseThrow();
            double width = maxX - minX, height = maxY - minY;
            double thickness = Math.min(width, height), length = Math.max(width, height);
            if (thickness <= 0 || thickness > 1d || length < 2d || length < thickness * 8d) return;
            Point from = width >= height ? new Point(minX, (minY + maxY) / 2d)
                    : new Point((minX + maxX) / 2d, minY);
            Point to = width >= height ? new Point(maxX, (minY + maxY) / 2d)
                    : new Point((minX + maxX) / 2d, maxY);
            int rgb;
            try { rgb = getGraphicsState().getNonStrokingColor().toRGB(); } catch (Exception ignored) { rgb = 0; }
            addLine(from, to, thickness, rgb);
        }

        private static double distance(Point from, Point to) {
            return Math.hypot(from.x() - to.x(), from.y() - to.y());
        }

        private void addLine(Point2D from, Point2D to) throws IOException {
            // PDFBox transforms path coordinates before invoking the graphics callbacks.
            // Applying the CTM here again moves/scales table borders away from their text.
            Point a = point(from.getX(), from.getY());
            Point b = point(to.getX(), to.getY());
            int rgb;
            try { rgb = getGraphicsState().getStrokingColor().toRGB(); } catch (Exception ignored) { rgb = 0; }
            addLine(a, b, Math.max(0.1d, getGraphicsState().getLineWidth() * page.userUnit() * MM_PER_POINT), rgb);
        }

        private void addLine(Point a, Point b, double width, int rgb) throws IOException {
            if (++entries > maxEntries) throw new IOException("PDF 图形对象数量超过限制");
            if (distance(a, b) < 0.5d) return;
            lines.add(new LineElement("pdf-p%d-line-%d".formatted(page.pageNumber(), entries), page.pageNumber(), a, b,
                    width,
                    new ColorValue((rgb >>> 16) & 0xff, (rgb >>> 8) & 0xff, rgb & 0xff, 255), entries));
        }

        private Rect rect(double x, double y, double width, double height) {
            double unit = page.userUnit();
            return new Rect(pointsToMm(x * unit), pointsToMm((page.pageBox().height() / MM_PER_POINT) - (y + height) * unit),
                    pointsToMm(width * unit), pointsToMm(height * unit));
        }

        private Point point(double x, double y) {
            double unit = page.userUnit();
            double localX = x - cropBox.getLowerLeftX();
            double localY = y - cropBox.getLowerLeftY();
            double displayedX;
            double displayedY;
            switch (page.rotation()) {
                case 90 -> { displayedX = localY; displayedY = localX; }
                case 180 -> { displayedX = cropBox.getWidth() - localX; displayedY = localY; }
                case 270 -> { displayedX = cropBox.getHeight() - localY; displayedY = cropBox.getWidth() - localX; }
                default -> { displayedX = localX; displayedY = cropBox.getHeight() - localY; }
            }
            return new Point(pointsToMm(displayedX * unit), pointsToMm(displayedY * unit));
        }
    }
}
