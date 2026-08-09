package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ColorValue;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.FontStyle;
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
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
                PageState state = pageState(page, index + 1, mode == ParseMode.EDITABLE_WORD);
                states.add(state);
            }

            LayoutTextStripper stripper = new LayoutTextStripper(states, limits.maxEntries());
            stripper.setSortByPosition(true);
            stripper.setShouldSeparateByBeads(false);
            stripper.setSuppressDuplicateOverlappingText(true);
            stripper.getText(document);

            List<PageModel> pages = new ArrayList<>(pageCount);
            for (PageState state : states) {
                if (mode.requiresExtractableText() && !state.hasEditableText() && state.hasVisibleContent()) {
                    throw new ConversionFailureException("OCR_REQUIRED",
                            "PDF 第 " + state.pageNumber() + " 页未检测到可编辑文字，可能是扫描件或纯图片 PDF；请先接入 OCR。");
                }
                List<ConversionWarning> warnings = new ArrayList<>();
                if (mode == ParseMode.EDITABLE_WORD && state.hasEditableText() && state.hasImageObjects()) {
                    warnings.add(ConversionWarning.of(WarningCode.IMAGE_EXTRACTION_FAILED,
                            "PDF 第 " + state.pageNumber() + " 页包含图片；当前可编辑路线仅恢复文字，图片尚未写入 Word。",
                            state.pageNumber()));
                }
                pages.add(new PageModel(state.pageNumber(), state.pageBox(), state.texts(), List.of(), List.of(),
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
        if (enforceWordPageLimit && (widthPoints > MAX_WORD_PAGE_POINTS || heightPoints > MAX_WORD_PAGE_POINTS)) {
            throw new ConversionFailureException("PAGE_SIZE_UNSUPPORTED",
                    "PDF 第 " + pageNumber + " 页尺寸超过 Word 支持的 22 英寸上限："
                            + formatPoints(widthPoints) + " × " + formatPoints(heightPoints) + " pt");
        }
        return new PageState(pageNumber,
                new Rect(0, 0, pointsToMm(widthPoints), pointsToMm(heightPoints)), userUnit, rotation,
                hasVisibleContent(page), hasImageObjects(page.getResources(),
                        java.util.Collections.newSetFromMap(new IdentityHashMap<>())), new ArrayList<>());
    }

    private static String formatPoints(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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

    private static double pointsToMm(double points) { return points * MM_PER_POINT; }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private enum ParseMode {
        EDITABLE_WORD(true),
        TEXT_EXTRACTION(true),
        FIXED_LAYOUT(false);

        private final boolean requiresExtractableText;

        ParseMode(boolean requiresExtractableText) {
            this.requiresExtractableText = requiresExtractableText;
        }

        boolean requiresExtractableText() { return requiresExtractableText; }
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
                             List<TextBlock> texts) {
        private boolean hasEditableText() {
            return texts.stream().anyMatch(text -> !text.text().isBlank());
        }
    }
}
