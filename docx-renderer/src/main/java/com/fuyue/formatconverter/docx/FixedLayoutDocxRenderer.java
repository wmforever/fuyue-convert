package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.*;
import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.math.BigInteger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Locale;

/**
 * Renders fixed-layout OFD pages as editable, absolutely positioned Word shapes.
 * Floating shapes do not participate in Word's reflow, so one source page stays
 * one output page while text remains editable inside text boxes.
 */
final class FixedLayoutDocxRenderer {
    private static final int BEHIND_TEXT_Z_INDEX = -251658752;
    private static final java.awt.font.FontRenderContext OCR_FONT_CONTEXT =
            new java.awt.font.FontRenderContext(null, true, true);
    private static final java.awt.Font OCR_CJK_FONT = loadOcrCjkFont();
    private static final java.awt.Font OCR_LATIN_COMPATIBLE_FONT = loadOcrLatinCompatibleFont();
    private int shapeSequence = 1;

    /**
     * Adds only page overlays for the semantic renderer. Normal text and
     * recognized tables stay in the Word body; only graphics and text whose
     * transform cannot be represented by a Word run remain floating shapes.
     */
    void renderOverlays(XWPFDocument docx, XWPFParagraph anchor, PageModel page,
                        List<TextBlock> fallbackTexts) throws Exception {
        renderOverlays(docx, anchor, page, fallbackTexts, Map.of());
    }

    void renderOverlays(XWPFDocument docx, XWPFParagraph anchor, PageModel page,
                        List<TextBlock> fallbackTexts, Map<TextBlock, XWPFParagraph> textAnchors) throws Exception {
        page.lines().stream().filter(line -> !insideAnyTable(line, page.tables()))
                .sorted(Comparator.comparingInt(LineElement::zOrder))
                .forEach(line -> unchecked(() -> addLine(anchor, line)));
        page.images().stream().sorted(Comparator.comparingInt(ImageBlock::zOrder))
                .forEach(image -> unchecked(() -> addImage(docx, anchor, image)));
        OcrAppearance ocrColors = addOcrMasks(anchor, page, fallbackTexts);
        // A heading separates independent layout regions. Sorting their union
        // can erase each region's valid gutter when columns shift horizontally.
        Map<XWPFParagraph, List<TextBlock>> regions = new java.util.LinkedHashMap<>();
        for (var text : fallbackTexts) regions.computeIfAbsent(textAnchors.getOrDefault(text, anchor),
                ignored -> new ArrayList<>()).add(text);
        for (var region : regions.entrySet()) {
            for (var text : fallbackReadingOrder(page, region.getValue())) {
                if (!text.text().isEmpty()) addTextBox(docx, region.getKey(), text, ocrColors);
            }
        }
    }

    private List<TextBlock> fallbackReadingOrder(PageModel page, List<TextBlock> texts) {
        List<TextBlock> sourceOrder = texts.stream()
                .sorted(Comparator.comparingInt(TextBlock::zOrder)).toList();
        // OCR has already supplied a reading sequence. A second gutter split
        // can interleave recognized columns when a heading bridges them.
        // Preserve that sequence, including uncertain words, without changing
        // their positions, layering, masks, or source scans.
        // Independent scan regions can each restart their local line ordinals.
        // Multiple backgrounds or duplicate ordinals are not one recognized
        // page sequence; retain the existing geometry policy instead of weaving
        // local sequences together or assuming image append order is intent.
        if (sourceOrder.stream().allMatch(block -> !block.ocrWords().isEmpty())
                && page.images().stream().filter(this::isOcrBackground).limit(2).count() <= 1
                && sourceOrder.stream().mapToInt(TextBlock::zOrder).distinct().count() == sourceOrder.size()) {
            return sourceOrder;
        }
        // Keep uncertain combinations in their existing order. Coordinates and
        // z-index remain unchanged even when plain, disjoint columns are ordered
        // for reading and copying the text from Word.
        if (!page.tables().isEmpty() || sourceOrder.stream().anyMatch(block ->
                Math.abs(block.transform().rotationDegrees()) > 0.5d
                        || block.transform().hasSkew(0.02d))) return sourceOrder;
        return orderColumns(sourceOrder, Math.max(12d, page.physicalBox().width() * 0.06d), false);
    }

    private List<TextBlock> orderColumns(List<TextBlock> texts, double minimumGap, boolean withinColumn) {
        if (texts.size() < 2) return texts;
        List<TextBlock> byX = texts.stream().sorted(Comparator.comparingDouble(block -> block.box().x())).toList();
        double right = byX.get(0).box().right();
        double largestGap = minimumGap;
        int split = -1;
        for (int index = 1; index < byX.size(); index++) {
            double gap = byX.get(index).box().x() - right;
            if (gap > largestGap) {
                largestGap = gap;
                split = index;
            }
            right = Math.max(right, byX.get(index).box().right());
        }
        if (split < 1) {
            return withinColumn ? texts.stream().sorted(Comparator.comparingDouble(TextBlock::baselineY)
                    .thenComparingDouble(block -> block.box().x())).toList() : texts;
        }
        List<TextBlock> ordered = new ArrayList<>(texts.size());
        ordered.addAll(orderColumns(byX.subList(0, split), minimumGap, true));
        ordered.addAll(orderColumns(byX.subList(split, byX.size()), minimumGap, true));
        return ordered;
    }

    private void unchecked(ThrowingAction action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new OverlayRenderException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private static final class OverlayRenderException extends RuntimeException {
        private OverlayRenderException(Exception cause) { super(cause); }
    }

    void render(XWPFDocument docx, List<PageModel> pages) throws Exception {
        for (int i = 0; i < pages.size(); i++) {
            PageModel page = pages.get(i);
            XWPFParagraph anchor = docx.createParagraph();
            configureAnchor(anchor);
            renderPage(docx, anchor, page);
            if (i < pages.size() - 1) {
                CTPPr pPr = anchor.getCTP().isSetPPr() ? anchor.getCTP().getPPr() : anchor.getCTP().addNewPPr();
                configureSection(pPr.addNewSectPr(), page.physicalBox(), true);
            } else {
                CTSectPr finalSection = docx.getDocument().getBody().isSetSectPr()
                        ? docx.getDocument().getBody().getSectPr()
                        : docx.getDocument().getBody().addNewSectPr();
                configureSection(finalSection, page.physicalBox(), false);
            }
        }
    }

    private void renderPage(XWPFDocument docx, XWPFParagraph anchor, PageModel page) throws Exception {
        List<FixedItem> items = new ArrayList<>();
        List<TextBlock> sourceTexts = page.textBlocks().isEmpty()
                ? page.paragraphs().stream().flatMap(paragraph -> paragraph.runs().stream()).toList()
                : page.textBlocks();
        OcrAppearance ocrColors = addOcrMasks(anchor, page, sourceTexts);
        page.lines().stream().filter(line -> !insideAnyTable(line, page.tables()))
                .forEach(line -> items.add(new FixedItem(line.zOrder(), line, null, null, null)));
        page.images().forEach(image -> items.add(new FixedItem(image.zOrder(), null, image, null, null)));
        sourceTexts.stream().filter(text -> !insideAnyTable(text, page.tables()))
                .forEach(text -> items.add(new FixedItem(text.zOrder(), null, null, text, null)));
        page.tables().forEach(table -> items.add(new FixedItem(tableZOrder(table, page), null, null, null, table)));
        items.sort(Comparator.comparingInt(FixedItem::zOrder));
        for (FixedItem item : items) {
            if (item.line() != null) addLine(anchor, item.line());
            else if (item.image() != null) addImage(docx, anchor, item.image());
            else if (item.text() != null && !item.text().text().isEmpty()) addTextBox(docx, anchor, item.text(), ocrColors);
            else if (item.table() != null) addTable(anchor, item.table(), item.zOrder());
        }
    }

    private boolean insideAnyTable(TextBlock text, List<TableModel> tables) {
        return tables.stream().anyMatch(table -> table.box().contains(text.box().center(), 0.2));
    }

    private boolean insideAnyTable(LineElement line, List<TableModel> tables) {
        return tables.stream().anyMatch(table -> table.box().contains(line.start(), 0.5)
                && table.box().contains(line.end(), 0.5));
    }

    private int tableZOrder(TableModel table, PageModel page) {
        return page.textBlocks().stream()
                .filter(text -> table.box().contains(text.box().center(), 0.2))
                .mapToInt(TextBlock::zOrder).min().orElse(0);
    }

    private void addTextBox(XWPFDocument docx, XWPFParagraph anchor, TextBlock block,
                            OcrAppearance ocrColors) throws Exception {
        if (!block.ocrWords().isEmpty()) {
            addOcrTextBoxes(docx, anchor, block, ocrColors);
            return;
        }
        addTextBox(docx, anchor, block, false);
    }

    private void addTextBox(XWPFDocument docx, XWPFParagraph anchor, TextBlock block, boolean ocr) throws Exception {
        addTextBox(docx, anchor, block, ocr, 0d);
    }

    private void addTextBox(XWPFDocument docx, XWPFParagraph anchor, TextBlock block, boolean ocr, double extraWidth) throws Exception {
        double topInsetMm = Math.max(0, block.textOffsetYmm() - block.style().sizePt() * 25.4d / 72d * 0.86d);
        Rect originalBox = tolerantTextBox(block);
        Rect textBox = new Rect(originalBox.x(), originalBox.y(), originalBox.width() + extraWidth, originalBox.height());
        double rotation = block.transform().rotationDegrees();
        String textFlow = "";
        if (Math.abs(Math.abs(rotation) - 90d) < 0.01d) {
            // Some Word-compatible readers rotate the VML box but leave its text
            // horizontal. Explicit vertical flow preserves editable quarter turns.
            double offset = (textBox.width() - textBox.height()) / 2d;
            textBox = new Rect(textBox.x() + offset, textBox.y() - offset,
                    textBox.height(), textBox.width());
            textFlow = "layout-flow:vertical;mso-layout-flow-alt:"
                    + (rotation > 0 ? "top-to-bottom;" : "bottom-to-top;");
            rotation = 0;
        }
        int characterSpacing = characterSpacingTwips(block);
        FontStyle font = block.style();
        String family = DocxFontSupport.familyFor(block);
        int horizontalScale = horizontalScalePercent(block);
        int halfPoints = Math.max(2, (int) Math.round(font.sizePt() * 2d));
        int lineTwips = Math.max(20, (int) Math.round(font.sizePt() * 20d));
        String runProperties = "<w:rPr>" +
                "<w:rFonts w:ascii=\"" + attr(font.family()) + "\" w:hAnsi=\"" + attr(font.family()) +
                "\" w:eastAsia=\"" + attr(family) + "\"/>" +
                "<w:sz w:val=\"" + halfPoints + "\"/><w:szCs w:val=\"" + halfPoints + "\"/>" +
                (horizontalScale == 100 ? "" : "<w:w w:val=\"" + horizontalScale + "\"/>") +
                (font.bold() ? "<w:b/><w:bCs/>" : "") +
                (font.italic() ? "<w:i/><w:iCs/>" : "") +
                "<w:color w:val=\"" + font.color().rgbHex() + "\"/>" +
                (characterSpacing == 0 ? "" : "<w:spacing w:val=\"" + characterSpacing + "\"/>") +
                "</w:rPr>";
        // A typeless v:shape acquires LibreOffice's default 0.15 cm frame padding
        // despite inset=0. A standard rect honors the explicit textbox insets.
        String element = ocr ? "rect" : "shape";
        String xml = "<v:" + element + " xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
                "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "id=\"" + attr(shapeId("text", block.id())) + "\" style=\"" +
                attr(positionStyle(textBox, block.zOrder(), rotation)) +
                "\" filled=\"f\" stroked=\"f\">" +
                "<v:textbox inset=\"" + pt(block.textOffsetXmm()) + "pt," + pt(topInsetMm) +
                "pt,0pt,0pt\" style=\"" + textFlow + "mso-fit-shape-to-text:false\">" +
                "<w:txbxContent><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"" +
                lineTwips + "\" w:lineRule=\"exact\"/></w:pPr><w:r>" + runProperties +
                "<w:t xml:space=\"preserve\">" + text(block.text()) + "</w:t></w:r></w:p></w:txbxContent>" +
                "</v:textbox></v:" + element + ">";
        appendShape(anchor, xml);
    }

    private OcrAppearance addOcrMasks(XWPFParagraph anchor, PageModel page, List<TextBlock> texts) {
        Map<TextBlock.OcrWord, ColorValue> colors = new IdentityHashMap<>();
        Map<TextBlock.OcrWord, Double> numericRightEdges = new IdentityHashMap<>();
        List<OcrMask> masks = new ArrayList<>();
        double inkHeightLimit = ocrInkHeightLimit(texts);
        double unreliableFontLimit = ocrUnreliableFontLimit(texts);
        if (texts.stream().allMatch(block -> block.ocrWords().isEmpty())) return new OcrAppearance(colors, numericRightEdges, inkHeightLimit, unreliableFontLimit);
        // LibreOffice paints negative VML shapes before a DrawingML scan anchor,
        // regardless of XML order. On a scan-only page, put sampled word masks
        // in front of that image and below the editable OCR boxes. A mixed page
        // needs a separate, conservative collision check against native body
        // text, including paragraphs absent from the fixed overlay list.
        boolean scanOnly = page.images().size() == 1 && page.lines().isEmpty() && page.tables().isEmpty()
                && texts.stream().allMatch(block -> !block.ocrWords().isEmpty())
                && page.textBlocks().stream().allMatch(block -> !block.ocrWords().isEmpty())
                && page.paragraphs().stream().flatMap(paragraph -> paragraph.runs().stream())
                        .allMatch(block -> !block.ocrWords().isEmpty());
        // Neighbor checks are quadratic in the word count; this optional edit
        // reserve is deliberately limited to sparse pages (at most 512² checks).
        boolean reserveEligible = scanOnly && texts.stream().mapToLong(block -> block.ocrWords().size()).sum() <= 512;
        List<Rect> nativeProtection = scanOnly ? null : mixedNativeProtection(page, texts);
        for (ImageBlock background : page.images()) {
            if (!isOcrBackground(background)) continue;
            // Decode one background at a time, rather than retaining all scan images on a page.
            try (OcrBackgroundMaskSampler sampler = OcrBackgroundMaskSampler.open(background)) {
                if (sampler == null) continue;
                for (TextBlock block : texts) for (TextBlock.OcrWord word : block.ocrWords()) {
                    // A small antialias fringe lies just outside Tesseract's ink bounds.
                    // Keep this per-word; never replace it with a union across unknown gaps.
                    double x = Math.max(word.box().x() - 0.15d, background.box().x());
                    double y = Math.max(word.box().y() - 0.15d, background.box().y());
                    double right = Math.min(word.box().right() + 0.15d, background.box().right());
                    double bottom = Math.min(word.box().bottom() + 0.15d, background.box().bottom());
                    if (right <= x || bottom <= y) continue;
                    Rect maskBox = new Rect(x, y, right - x, bottom - y);
                    List<OcrBackgroundMaskSampler.Fill> fills = sampler.fills(maskBox);
                    if (fills.isEmpty()) continue;
                    // A grid-sized low-confidence word cannot justify erasing
                    // the underlying table or unknown fields. Keep its editable
                    // prediction, but leave that source region exposed.
                    if (scanOnly && anomalousWord(word, inkHeightLimit) && fills.stream().allMatch(fill -> {
                        int rgb = Integer.parseInt(fill.color(), 16);
                        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                        return Math.min(r, Math.min(g, b)) >= 110
                                && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 24;
                    })) continue;
                    boolean mixedForeground = nativeProtection != null && word.confidence() >= .85d
                            && block.zOrder() >= 1 && Transform2D.IDENTITY.equals(block.transform())
                            && word.box().x() >= background.box().x() && word.box().right() <= background.box().right()
                            && word.box().y() >= background.box().y() && word.box().bottom() <= background.box().bottom()
                            && lightNeutralPaper(fills)
                            && nativeProtection.stream().noneMatch(box -> box.intersectionArea(maskBox) > 0d);
                    boolean lightDecimal = scanOnly && word.confidence() >= .85d
                            && word.text().matches("[+-]?[0-9]{1,12}\\.[0-9]{1,6}")
                            && Transform2D.IDENTITY.equals(block.transform())
                            && word.box().width() < page.physicalBox().width() / 4d
                            && word.box().height() < page.physicalBox().height() / 12d
                            && word.box().x() >= background.box().x() && word.box().right() <= background.box().right()
                            && word.box().y() >= background.box().y() && word.box().bottom() <= background.box().bottom()
                            && lightNeutralPaper(fills);
                    for (OcrBackgroundMaskSampler.Fill fill : fills) {
                        // White screenshot letters must not move independently
                        // sampled, reliable light-paper prose behind the scan.
                        // Numeric and dark-paper words retain their existing policy.
                        boolean boundedLightWord = scanOnly && word.confidence() >= .85d
                                && word.text().codePoints().noneMatch(Character::isDigit)
                                && Transform2D.IDENTITY.equals(block.transform())
                                && word.box().width() < page.physicalBox().width() / 4d
                                && word.box().height() < page.physicalBox().height() / 12d
                                && word.box().x() >= background.box().x() && word.box().right() <= background.box().right()
                                && word.box().y() >= background.box().y() && word.box().bottom() <= background.box().bottom()
                                && lightNeutralPaper(fills);
                        masks.add(new OcrMask(block.id(), fill, mixedForeground, lightDecimal, boundedLightWord));
                    }
                    colors.put(word, ocrForeground(fills, block.style().color()));
                    // Reserve only a bounded, pixel-checked blank region for a
                    // last numeric word. The transparent box grows; masks and
                    // source coordinates do not. Dense/annotated pages fall back.
                    if (reserveEligible && fills.size() == 1 && word.confidence() >= .85d
                            && Math.abs(block.transform().rotationDegrees()) < .01d && !block.transform().hasSkew(.001d)
                            && block.ocrWords().get(block.ocrWords().size() - 1) == word
                            && word.text().matches("[+-]?[0-9]{1,12}(?:\\.[0-9]{1,6})?")) {
                        double edge = Math.min(Math.min(background.box().right(), page.physicalBox().right()) - .5d,
                                word.box().right() + Math.min(15d, word.box().height() * 4.5d));
                        double left = word.box().right() + .2d;
                        double top = word.box().y() - word.box().height() * .5d;
                        if (edge > left && top >= background.box().y()) {
                            Rect reserve = new Rect(left, top, edge - left, word.box().height() * 2.1d);
                            boolean overlaps = texts.stream().flatMap(t -> t.ocrWords().stream())
                                    .anyMatch(other -> other != word && reserve.intersectionArea(other.box()) > 0d);
                            if (!overlaps && sampler.uniformLightPaper(reserve, fills.get(0).color())) {
                                numericRightEdges.put(word, edge);
                                // Longer edits may need more than the original short reserve.
                                // Inspect only the additional bounded strip; if it contains
                                // unknown ink, a neighbor, or exceeds the same pixel budget,
                                // retain the already verified short reserve.
                                double extendedEdge = Math.min(Math.min(background.box().right(), page.physicalBox().right()) - .5d,
                                        word.box().right() + Math.min(25d, word.box().height() * 8d));
                                if (extendedEdge > edge) {
                                    Rect extension = new Rect(edge, top, extendedEdge - edge, reserve.height());
                                    boolean neighbor = texts.stream().flatMap(t -> t.ocrWords().stream())
                                            .anyMatch(other -> other != word && extension.intersectionArea(other.box()) > 0d);
                                    if (!neighbor && sampler.uniformLightPaper(extension, fills.get(0).color())) {
                                        numericRightEdges.put(word, extendedEdge);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // Optional background estimation cannot justify an uninformed white cover.
            }
        }
        // LibreOffice 24.2's dark-paper regression fails with foreground masks
        // even though 26.8 renders the same white OCR letters. Keep the entire
        // page on its validated old layering if any sampled word needs white
        // foreground. The narrow decimal exception below retains all white-word
        // masks on that old policy and does not enable edit reserves.
        boolean foregroundMasks = scanOnly && colors.values().stream().noneMatch(ColorValue.WHITE::equals);
        // A broad, uncertain grid-sized word can sample a black rule as paper.
        // Retain its white text and old mask policy, while independently proven
        // light-paper decimal masks cover their own original scan ink. Never
        // promote white-letter masks or grant numeric edit reserves on this path.
        boolean anomalousDarkWord = scanOnly && colors.entrySet().stream().anyMatch(entry ->
                ColorValue.WHITE.equals(entry.getValue()) && entry.getKey().confidence() < .35d
                        && entry.getKey().box().width() >= page.physicalBox().width() / 2d
                        && entry.getKey().box().height() >= page.physicalBox().height() / 10d);
        if (!foregroundMasks) numericRightEdges.clear();
        for (OcrMask mask : masks) {
            var fill = mask.fill();
            String xml = "<v:rect xmlns:v=\"urn:schemas-microsoft-com:vml\" id=\""
                    + attr(shapeId("ocr-mask", mask.blockId())) + "\" style=\""
                    + attr(positionStyle(fill.box(), foregroundMasks || mask.mixedForeground()
                            || anomalousDarkWord && mask.lightDecimal() || mask.boundedLightWord()
                            ? 1 : BEHIND_TEXT_Z_INDEX + 1, 0, true))
                    + "\" filled=\"t\" fillcolor=\"#" + fill.color() + "\" stroked=\"f\"/>";
            unchecked(() -> appendShape(anchor, xml));
        }
        return new OcrAppearance(colors, numericRightEdges, inkHeightLimit, unreliableFontLimit);
    }

    private boolean anomalousWord(TextBlock.OcrWord word, double inkHeightLimit) {
        return word.confidence() < .85d && (word.box().height() > inkHeightLimit * .75d
                || word.box().width() > Math.max(1, word.text().codePointCount(0, word.text().length()))
                    * word.box().height() * 3d);
    }

    private double ocrUnreliableFontLimit(List<TextBlock> texts) {
        double[] sizes = texts.stream().flatMap(block -> block.ocrWords().stream())
                .filter(word -> word.confidence() >= .85d && word.text().codePoints().anyMatch(Character::isLetterOrDigit))
                .mapToDouble(word -> {
                    var glyph = ocrFont(word.text()).deriveFont(100f).createGlyphVector(OCR_FONT_CONTEXT, word.text()).getVisualBounds();
                    return Math.min(word.box().height() * 72d / 25.4d * 100d / Math.max(10d, glyph.getHeight()),
                            word.box().width() * 72d / 25.4d * 100d / Math.max(1d, glyph.getWidth()) / .6d);
                }).sorted().toArray();
        return sizes.length < 20 ? Double.POSITIVE_INFINITY : Math.max(5d, sizes[sizes.length / 2]);
    }

    private double ocrInkHeightLimit(List<TextBlock> texts) {
        double[] heights = texts.stream().flatMap(block -> block.ocrWords().stream())
                .filter(word -> word.confidence() >= .85d && word.box().height() > 0
                        && word.text().codePoints().anyMatch(Character::isLetterOrDigit))
                .mapToDouble(word -> word.box().height()).sorted().toArray();
        // A populated scan supplies a robust scale. Sparse title pages have no
        // body-text evidence and must retain their original large typography.
        return heights.length < 20 ? Double.POSITIVE_INFINITY : heights[heights.length / 2] * 2d;
    }

    /** Bounded support for sparse, disjoint native/OCR pages; ambiguous layouts retain their scan. */
    private List<Rect> mixedNativeProtection(PageModel page, List<TextBlock> texts) {
        if (page.images().size() != 1 || !isOcrBackground(page.images().get(0))
                || !page.lines().isEmpty() || !page.tables().isEmpty()
                || page.textBlocks().size() > 512 || page.paragraphs().size() > 512 || texts.size() > 512
                || texts.stream().mapToLong(block -> block.ocrWords().size()).sum() > 512) return null;
        Map<TextBlock, Boolean> blocks = new IdentityHashMap<>();
        page.textBlocks().forEach(block -> blocks.put(block, true));
        texts.forEach(block -> blocks.put(block, true));
        List<Rect> protectedBoxes = new ArrayList<>();
        int references = page.textBlocks().size() + texts.size();
        try {
            for (ParagraphModel paragraph : page.paragraphs()) {
                if (paragraph.flow() != null || (references += paragraph.runs().size()) > 1536) return null;
                double padding = 0d;
                for (TextBlock block : paragraph.runs()) {
                    blocks.put(block, true);
                    if (!block.text().isEmpty() && block.ocrWords().isEmpty()) {
                        padding = Math.max(padding, nativePadding(block));
                    }
                }
                if (padding > 0d) protectedBoxes.add(expandNativeBox(paragraph.box(), padding));
            }
            for (TextBlock block : blocks.keySet()) {
                if (!block.text().isEmpty() && block.ocrWords().isEmpty()) {
                    protectedBoxes.add(expandNativeBox(block.box(), nativePadding(block)));
                }
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        // At most 512 words × 512 protected regions. Do not promote any mask on
        // a dense page or a page whose native geometry cannot be trusted.
        return protectedBoxes.isEmpty() || protectedBoxes.size() > 512 ? null : protectedBoxes;
    }

    private double nativePadding(TextBlock block) {
        if (!Transform2D.IDENTITY.equals(block.transform()) || !Double.isFinite(block.style().sizePt())
                || !Double.isFinite(block.textOffsetXmm()) || !Double.isFinite(block.textOffsetYmm())
                || block.textOffsetXmm() > block.box().width() || block.textOffsetYmm() > block.box().height()
                || !Double.isFinite(block.baselineY()) || block.baselineY() < block.box().y()
                || block.baselineY() > block.box().bottom()) {
            throw new IllegalArgumentException("Uncertain native text geometry");
        }
        // Body text may move within its paragraph; protect two font heights
        // around both source runs and their containing native paragraphs.
        return block.style().sizePt() * 25.4d / 72d * 2d;
    }

    private Rect expandNativeBox(Rect box, double padding) {
        return new Rect(box.x() - padding, box.y() - padding,
                box.width() + padding * 2d, box.height() + padding * 2d);
    }

    private boolean lightNeutralPaper(List<OcrBackgroundMaskSampler.Fill> fills) {
        return fills.stream().allMatch(fill -> {
            int rgb = Integer.parseInt(fill.color(), 16);
            int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
            int minimum = Math.min(r, Math.min(g, b)), maximum = Math.max(r, Math.max(g, b));
            return minimum >= 180 && maximum - minimum <= 8;
        });
    }

    private record OcrMask(String blockId, OcrBackgroundMaskSampler.Fill fill,
                           boolean mixedForeground, boolean lightDecimal, boolean boundedLightWord) { }
    private record OcrAppearance(Map<TextBlock.OcrWord, ColorValue> colors,
                                 Map<TextBlock.OcrWord, Double> numericRightEdges, double inkHeightLimit,
                                 double unreliableFontLimit) { }

    private ColorValue ocrForeground(List<OcrBackgroundMaskSampler.Fill> fills, ColorValue original) {
        double brightness = 0, area = 0;
        for (OcrBackgroundMaskSampler.Fill fill : fills) {
            int rgb = Integer.parseInt(fill.color(), 16);
            double weight = fill.box().width() * fill.box().height();
            brightness += (((rgb >>> 16) & 255) * .299 + ((rgb >>> 8) & 255) * .587 + (rgb & 255) * .114) * weight;
            area += weight;
        }
        return area > 0 && brightness / area < 110 ? ColorValue.WHITE : original;
    }

    /** Position each recognized word separately so unknown content in the gaps stays exposed. */
    private void addOcrTextBoxes(XWPFDocument docx, XWPFParagraph anchor, TextBlock line,
                                 OcrAppearance ocrColors) throws Exception {
        List<Double> fontSizes = new ArrayList<>();
        double[] heights = line.ocrWords().stream()
                .filter(word -> word.text().codePoints().anyMatch(Character::isLetterOrDigit))
                .mapToDouble(word -> word.box().height()).sorted().toArray();
        double heightLimit = Math.min(ocrColors.inkHeightLimit(), heights.length == 0
                ? Double.POSITIVE_INFINITY : heights[heights.length / 2] * 2d);
        for (TextBlock.OcrWord word : line.ocrWords()) {
            if (word.text().codePoints().noneMatch(Character::isLetterOrDigit)) continue;
            java.awt.Font font = ocrFont(word.text()).deriveFont(100f);
            double glyphHeight = font.createGlyphVector(OCR_FONT_CONTEXT, word.text()).getVisualBounds().getHeight();
            if (glyphHeight > 10d) {
                var glyphs = font.createGlyphVector(OCR_FONT_CONTEXT, word.text()).getVisualBounds();
                double byHeight = Math.min(word.box().height(), heightLimit) * 72d / 25.4d * 100d / glyphHeight;
                double byWidth = word.box().width() * 72d / 25.4d * 100d / Math.max(1d, glyphs.getWidth());
                fontSizes.add(Math.min(byHeight, byWidth / .6d));
            }
        }
        fontSizes.sort(Double::compareTo);
        double sizePt = Math.max(5d, Math.min(72d, fontSizes.isEmpty() ? line.style().sizePt()
                : fontSizes.get(fontSizes.size() / 2)));
        double fontMm = sizePt * 25.4d / 72d;
        double top = Math.max(0d, line.box().y() - fontMm * 0.12d);
        int offset = 0;
        for (int index = 0; index < line.ocrWords().size(); index++) {
            TextBlock.OcrWord word = line.ocrWords().get(index);
            String value = word.text();
            int found = line.text().indexOf(value, offset);
            int end = found < 0 ? offset + value.length() : found + value.length();
            // Preserve the line's word separators in the XML reading/copy order without
            // inserting a second hidden or visible copy of the recognized line.
            if (end < line.text().length() && Character.isWhitespace(line.text().charAt(end))) value += " ";
            offset = end;
            java.awt.Font font = ocrFont(word.text()).deriveFont(100f);
            java.awt.geom.Rectangle2D glyphs = font.createGlyphVector(OCR_FONT_CONTEXT, word.text()).getVisualBounds();
            double wordSizePt = word.text().codePoints().noneMatch(Character::isLetterOrDigit) ? sizePt
                    : Math.max(5d, Math.min(sizePt,
                    Math.min(Math.min(word.box().height(), heightLimit) * 72d / 25.4d * 100d / Math.max(10d, glyphs.getHeight()),
                            word.box().width() * 72d / 25.4d * 100d / Math.max(1d, glyphs.getWidth()) / .6d)));
            if (anomalousWord(word, ocrColors.inkHeightLimit())) wordSizePt = Math.min(wordSizePt, ocrColors.unreliableFontLimit());
            double wordFontMm = wordSizePt * 25.4d / 72d;
            // Tesseract can label fragments from different table rows as a single line.
            // Do not pull a lower word up to the union's top edge.
            double wordTop = word.box().y() - line.box().y() > fontMm * .5d
                    ? Math.max(0d, word.box().y() - wordFontMm * .12d) : top;
            double inkWidthPt = glyphs.getWidth() * wordSizePt / 100d;
            double ratio = word.box().width() * 72d / 25.4d / Math.max(1d, inkWidthPt);
            ratio = Math.max(0.6d, Math.min(1.4d, ratio));
            double bearingMm = glyphs.getX() * wordSizePt / 100d * 25.4d / 72d * ratio;
            Rect box = new Rect(Math.max(0d, word.box().x() - bearingMm), wordTop,
                    word.box().width(), Math.max(word.box().height(), wordFontMm * 1.3d));
            TextBlock positioned = new TextBlock(line.id() + "-word-" + index, line.pageNumber(), box,
                    value, line.baselineY(), new FontStyle("Arial", wordSizePt, false, false, ocrColors.colors().getOrDefault(word, line.style().color())),
                    Math.max(1, line.zOrder()), 0, 0, List.of(), new Transform2D(ratio, 0, 0, 1, 0, 0));
            if (Math.abs(line.transform().rotationDegrees()) < .01d && !line.transform().hasSkew(.001d)) {
                double wrappingWidth = tolerantTextBox(positioned).width();
                double availableWidth = wrappingWidth;
                if (index + 1 < line.ocrWords().size()) {
                    TextBlock.OcrWord next = line.ocrWords().get(index + 1);
                    availableWidth = latinWordWidthBeforeNext(word, next, ocrFont(next.text()), wordSizePt,
                            box, availableWidth);
                }
                double fitted = latinWordScale(word, font, OCR_LATIN_COMPATIBLE_FONT, wordSizePt, ratio,
                        wrappingWidth, availableWidth);
                if (fitted != ratio) positioned = new TextBlock(positioned.id(), positioned.pageNumber(),
                        positioned.box(), positioned.text(), positioned.baselineY(), positioned.style(), positioned.zOrder(),
                        positioned.textOffsetXmm(), positioned.textOffsetYmm(), positioned.advancesMm(),
                        new Transform2D(fitted, 0, 0, 1, 0, 0));
            }
            double extraWidth = Math.max(0d,
                    ocrColors.numericRightEdges().getOrDefault(word, 0d) - tolerantTextBox(positioned).right());
            addTextBox(docx, anchor, positioned, true, extraWidth);
        }
    }

    private java.awt.Font ocrFont(String text) {
        return DocxFontSupport.containsCjkText(text) && OCR_CJK_FONT != null
                ? OCR_CJK_FONT : new java.awt.Font("Arial", java.awt.Font.PLAIN, 100);
    }

    /** The transparent wrapping allowance must not consume a reliable neighboring word's gap. */
    static double latinWordWidthBeforeNext(TextBlock.OcrWord word, TextBlock.OcrWord next,
                                          java.awt.Font nextFont, double sizePt, Rect positioned,
                                          double defaultWidth) {
        double gap = next.box().x() - word.box().right();
        if (gap <= 0d || next.confidence() < .85d
                || next.box().y() >= word.box().bottom() || next.box().bottom() <= word.box().y()) {
            return defaultWidth;
        }
        var glyphs = nextFont.deriveFont(100f).createGlyphVector(OCR_FONT_CONTEXT, next.text()).getVisualBounds();
        double ratio = next.box().width() * 72d / 25.4d / Math.max(1d, glyphs.getWidth() * sizePt / 100d);
        ratio = Math.max(.6d, Math.min(1.4d, ratio));
        double bearing = glyphs.getX() * sizePt / 100d * 25.4d / 72d * ratio;
        double nextLeft = Math.max(0d, next.box().x() - bearing);
        double available = nextLeft - positioned.x() - gap * .5d;
        // Ambiguous/overlapping geometry retains the established fallback.
        return available > 0d ? Math.min(defaultWidth, available) : defaultWidth;
    }

    /** Correct only a proven width overflow when Java silently substitutes Dialog for Arial. */
    static double latinWordScale(TextBlock.OcrWord word, java.awt.Font measuredFont,
                                 java.awt.Font compatibleFont, double sizePt, double original,
                                 double wrappingWidthMm, double neighborWidthMm) {
        double established = latinWordScale(word, measuredFont, compatibleFont, sizePt, original, wrappingWidthMm);
        if (neighborWidthMm >= wrappingWidthMm) return established;
        // Failure to prove a tighter fit must never undo the established fix.
        return Math.min(established,
                latinWordScale(word, measuredFont, compatibleFont, sizePt, original, neighborWidthMm));
    }

    static double latinWordScale(TextBlock.OcrWord word, java.awt.Font measuredFont,
                                 java.awt.Font compatibleFont, double sizePt, double original, double boxWidthMm) {
        if (compatibleFont == null || !"Dialog".equals(measuredFont.getFamily())
                || word.confidence() < .85d || !word.text().matches("[A-Za-z]{3,32}")) return original;
        // Match the actual half-point font size and integer OOXML percentage.
        float emittedSize = (float) (Math.max(2, Math.round(sizePt * 2d)) / 2d);
        var glyphs = compatibleFont.deriveFont(emittedSize).createGlyphVector(OCR_FONT_CONTEXT, word.text());
        double advance = glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX();
        double availablePt = boxWidthMm * 72d / 25.4d;
        if (advance * Math.round(original * 100d) / 100d <= availablePt) return original;
        var actualInk = compatibleFont.deriveFont(100f).createGlyphVector(OCR_FONT_CONTEXT, word.text()).getVisualBounds();
        var measuredInk = measuredFont.deriveFont(100f).createGlyphVector(OCR_FONT_CONTEXT, word.text()).getVisualBounds();
        if (actualInk.getWidth() <= 1d || measuredInk.getWidth() <= 1d) return original;
        double fitted = original * measuredInk.getWidth() / actualInk.getWidth();
        // Only contract the wrongly overestimated Latin scale. Source box,
        // bearing, masks, line size, numeric words and edit reserves stay intact.
        if (!Double.isFinite(fitted) || fitted < .6d || fitted >= original
                || advance * Math.round(fitted * 100d) / 100d > availablePt) return original;
        return fitted;
    }

    private static java.awt.Font loadOcrLatinCompatibleFont() {
        java.awt.Font system = new java.awt.Font("Liberation Sans", java.awt.Font.PLAIN, 100);
        if ("Liberation Sans".equals(system.getFamily())) return system;
        try (var input = FixedLayoutDocxRenderer.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return input == null ? null : java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, input).deriveFont(100f);
        } catch (Exception ignored) { return null; }
    }

    private static java.awt.Font loadOcrCjkFont() {
        try (var input = FixedLayoutDocxRenderer.class.getResourceAsStream("/fonts/DroidSansFallback.ttf")) {
            return input == null ? null : java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, input);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * OFD boundaries are measured with the embedded source font. Word may use a
     * metrically different substitute even when the family name is preserved;
     * a small transparent overflow area prevents the last one or two CJK glyphs
     * from wrapping without changing their source coordinates.
     */
    private Rect tolerantTextBox(TextBlock block) {
        double fontSizeMm = block.style().sizePt() * 25.4d / 72d;
        double horizontalRatio = horizontalScalePercent(block) / 100d;
        double layoutCompensation = horizontalRatio < 1d
                ? block.box().width() * (1d / horizontalRatio - 1d) : 0d;
        double overflowMm = Math.max(1.5d, Math.max(fontSizeMm * 0.8d,
                layoutCompensation + fontSizeMm * 0.5d));
        return new Rect(block.box().x(), block.box().y(),
                block.box().width() + overflowMm, block.box().height());
    }

    private void addLine(XWPFParagraph anchor, LineElement line) throws Exception {
        if (!line.horizontal(0.15) && !line.vertical(0.15)) {
            String xml = "<v:line xmlns:v=\"urn:schemas-microsoft-com:vml\" id=\"" +
                    attr(shapeId("line", line.id())) + "\" style=\"position:absolute;z-index:" +
                    Math.max(1, line.zOrder() + 1) +
                    ";mso-position-horizontal-relative:page;mso-position-vertical-relative:page\" from=\"" +
                    pt(line.start().x()) + "pt," + pt(line.start().y()) + "pt\" to=\"" +
                    pt(line.end().x()) + "pt," + pt(line.end().y()) + "pt\" strokecolor=\"#" +
                    line.color().rgbHex() + "\" strokeweight=\"" + pt(line.widthMm()) + "pt\"/>";
            appendShape(anchor, xml);
            return;
        }
        double x = line.minX();
        double y = line.minY();
        double width = Math.max(line.maxX() - line.minX(), line.widthMm());
        double height = Math.max(line.maxY() - line.minY(), line.widthMm());
        if (line.horizontal(0.15)) y -= line.widthMm() / 2d;
        if (line.vertical(0.15)) x -= line.widthMm() / 2d;

        String xml = "<v:rect xmlns:v=\"urn:schemas-microsoft-com:vml\" id=\"" +
                attr(shapeId("line", line.id())) + "\" style=\"" +
                attr(positionStyle(new Rect(x, y, width, height), line.zOrder())) +
                "\" filled=\"t\" fillcolor=\"#" + line.color().rgbHex() + "\" stroked=\"f\"/>";
        appendShape(anchor, xml);
    }

    private void addImage(XWPFDocument docx, XWPFParagraph anchor, ImageBlock image) throws Exception {
        if (image.data().length == 0) return;
        String relationId = docx.addPictureData(image.data(), pictureType(image.mimeType()));
        if (isOcrBackground(image)) {
            addScanBackground(anchor, image, relationId);
            return;
        }
        String position = isOcrBackground(image)
                ? positionStyle(image.box(), BEHIND_TEXT_Z_INDEX, 0, true)
                : positionStyle(image.box(), image.zOrder());
        String xml = "<v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "id=\"" + attr(shapeId("image", image.id())) + "\" style=\"" +
                attr(position) + "\" filled=\"f\" stroked=\"f\">" +
                "<v:imagedata r:id=\"" + attr(relationId) + "\" title=\"" + attr(image.id()) + "\"/>" +
                "</v:shape>";
        appendShape(anchor, xml);
    }

    private void addScanBackground(XWPFParagraph anchor, ImageBlock image, String relationId) throws Exception {
        Rect box = image.box();
        long x = Math.round(box.x() * 36000), y = Math.round(box.y() * 36000);
        long width = Math.max(1, Math.round(box.width() * 36000));
        long height = Math.max(1, Math.round(box.height() * 36000));
        int id = shapeSequence++;
        String xml = "<wp:anchor xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"0\" "
                + "behindDoc=\"1\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + x
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + y
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + width + "\" cy=\"" + height + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"" + id + "\" name=\"" + attr(image.id()) + "\"/>"
                + "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"" + attr(image.id())
                + "\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"" + attr(relationId)
                + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm>"
                + "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + width + "\" cy=\"" + height
                + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:anchor>";
        copyInto(anchor.createRun().getCTR().addNewDrawing(), XmlObject.Factory.parse(xml));
    }

    private boolean isOcrBackground(ImageBlock image) {
        return "OCR_SCAN_BACKGROUND".equals(image.role())
                || "OCR_PAGE_BACKGROUND".equals(image.role());
    }

    private void addTable(XWPFParagraph anchor, TableModel table, int zOrder) throws Exception {
        if (table.rowCount() == 0 || table.columnCount() == 0) return;
        String xml = "<v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
                "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" id=\"" +
                attr(shapeId("table", table.id())) + "\" style=\"" +
                attr(positionStyle(table.box(), zOrder)) + "\" filled=\"f\" stroked=\"f\">" +
                "<v:textbox inset=\"0pt,0pt,0pt,0pt\" style=\"mso-fit-shape-to-text:false\">" +
                "<w:txbxContent>" + tableXml(table) + "<w:p/></w:txbxContent>" +
                "</v:textbox></v:shape>";
        appendShape(anchor, xml);
    }

    private String tableXml(TableModel table) {
        StringBuilder xml = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"")
                .append(twips(table.box().width())).append("\" w:type=\"dxa\"/>")
                .append("<w:tblLayout w:type=\"fixed\"/><w:tblCellMar>")
                .append("<w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"0\" w:type=\"dxa\"/>")
                .append("<w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"0\" w:type=\"dxa\"/>")
                .append("</w:tblCellMar></w:tblPr><w:tblGrid>");
        for (int column = 0; column < table.columnCount(); column++) {
            xml.append("<w:gridCol w:w=\"").append(columnWidth(table, column)).append("\"/>");
        }
        xml.append("</w:tblGrid>");
        for (int row = 0; row < table.rowCount(); row++) {
            xml.append("<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val=\"")
                    .append(twips(table.yGrid().get(row + 1) - table.yGrid().get(row)))
                    .append("\" w:hRule=\"atLeast\"/></w:trPr>");
            int column = 0;
            while (column < table.columnCount()) {
                CellModel cell = coveringCell(table, row, column);
                int span = cell == null ? 1 : cell.columnSpan();
                xml.append(cellXml(table, cell, row, column, span));
                column += span;
            }
            xml.append("</w:tr>");
        }
        return xml.append("</w:tbl>").toString();
    }

    private CellModel coveringCell(TableModel table, int row, int column) {
        return table.cells().stream().filter(cell -> row >= cell.row() && row < cell.row() + cell.rowSpan()
                        && column == cell.column())
                .findFirst().orElse(null);
    }

    private String cellXml(TableModel table, CellModel cell, int row, int column, int span) {
        int width = 0;
        for (int current = column; current < Math.min(table.columnCount(), column + span); current++) {
            width += columnWidth(table, current);
        }
        StringBuilder xml = new StringBuilder("<w:tc><w:tcPr><w:tcW w:w=\"")
                .append(width).append("\" w:type=\"dxa\"/>");
        if (span > 1) xml.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
        if (cell != null && cell.rowSpan() > 1) {
            xml.append("<w:vMerge w:val=\"").append(row == cell.row() ? "restart" : "continue").append("\"/>");
        }
        if (cell != null) {
            xml.append("<w:vAlign w:val=\"").append(switch (cell.verticalAlignment()) {
                case TOP -> "top";
                case CENTER -> "center";
                case BOTTOM -> "bottom";
            }).append("\"/>");
            if (cell.fill() != null) xml.append("<w:shd w:fill=\"").append(cell.fill().rgbHex()).append("\"/>");
            xml.append(bordersXml(cell));
        }
        xml.append("</w:tcPr>");
        if (cell == null || row != cell.row() || cell.paragraphs().isEmpty()) {
            xml.append("<w:p/>");
        } else {
            for (ParagraphModel paragraph : cell.paragraphs()) xml.append(tableParagraphXml(paragraph, cell.horizontalAlignment()));
        }
        return xml.append("</w:tc>").toString();
    }

    private int columnWidth(TableModel table, int column) {
        return twips(table.xGrid().get(column + 1) - table.xGrid().get(column));
    }

    private String bordersXml(CellModel cell) {
        return "<w:tcBorders>" + borderXml("top", cell.top()) + borderXml("right", cell.right()) +
                borderXml("bottom", cell.bottom()) + borderXml("left", cell.left()) + "</w:tcBorders>";
    }

    private String borderXml(String side, BorderStyle border) {
        if (border == null || border.pattern() == BorderStyle.Pattern.NONE) {
            return "<w:" + side + " w:val=\"nil\"/>";
        }
        int size = Math.max(2, (int) Math.round(border.widthMm() * 72d / 25.4d * 8d));
        return "<w:" + side + " w:val=\"single\" w:sz=\"" + size + "\" w:color=\"" +
                border.color().rgbHex() + "\"/>";
    }

    private String tableParagraphXml(ParagraphModel paragraph, ParagraphModel.Alignment fallback) {
        ParagraphModel.Alignment alignment = fallback == null ? paragraph.alignment() : fallback;
        StringBuilder xml = new StringBuilder("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/>")
                .append("<w:jc w:val=\"").append(switch (alignment) {
                    case CENTER -> "center";
                    case RIGHT -> "right";
                    case JUSTIFY -> "both";
                    default -> "left";
                }).append("\"/></w:pPr>");
        for (TextBlock run : paragraph.runs()) {
            FontStyle font = run.style();
            String family = DocxFontSupport.familyFor(run);
            int halfPoints = Math.max(2, (int) Math.round(font.sizePt() * 2d));
            xml.append("<w:r><w:rPr><w:rFonts w:ascii=\"").append(attr(family))
                    .append("\" w:hAnsi=\"").append(attr(family)).append("\" w:eastAsia=\"")
                    .append(attr(family)).append("\"/><w:sz w:val=\"").append(halfPoints)
                    .append("\"/><w:szCs w:val=\"").append(halfPoints).append("\"/>");
            if (font.bold()) xml.append("<w:b/><w:bCs/>");
            if (font.italic()) xml.append("<w:i/><w:iCs/>");
            xml.append("<w:color w:val=\"").append(font.color().rgbHex()).append("\"/></w:rPr>")
                    .append("<w:t xml:space=\"preserve\">").append(text(run.text())).append("</w:t></w:r>");
        }
        return xml.append("</w:p>").toString();
    }

    private void appendShape(XWPFParagraph anchor, String shapeXml) throws Exception {
        CTPicture picture = anchor.createRun().getCTR().addNewPict();
        XmlObject shape = XmlObject.Factory.parse(shapeXml);
        copyInto(picture, shape);
    }

    private void copyInto(XmlObject parent, XmlObject child) {
        try (XmlCursor destination = parent.newCursor(); XmlCursor source = child.newCursor()) {
            destination.toEndToken();
            source.toNextToken();
            source.copyXml(destination);
        }
    }

    private void configureAnchor(XWPFParagraph paragraph) {
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(1, LineSpacingRule.EXACT);
        CTPPr pPr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTSpacing spacing = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        spacing.setBefore(BigInteger.ZERO);
        spacing.setAfter(BigInteger.ZERO);
        spacing.setLine(BigInteger.ONE);
        spacing.setLineRule(STLineSpacingRule.EXACT);
    }

    private void configureSection(CTSectPr section, Rect page, boolean nextPage) {
        if (nextPage) {
            CTSectType type = section.isSetType() ? section.getType() : section.addNewType();
            type.setVal(STSectionMark.NEXT_PAGE);
        }
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(twips(page.width())));
        size.setH(BigInteger.valueOf(twips(page.height())));
        if (page.width() > page.height()) size.setOrient(STPageOrientation.LANDSCAPE);
        CTPageMar margin = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margin.setTop(BigInteger.ZERO);
        margin.setBottom(BigInteger.ZERO);
        margin.setLeft(BigInteger.ZERO);
        margin.setRight(BigInteger.ZERO);
        margin.setHeader(BigInteger.ZERO);
        margin.setFooter(BigInteger.ZERO);
        margin.setGutter(BigInteger.ZERO);
    }

    private int characterSpacingTwips(TextBlock block) {
        int[] codePoints = block.text().codePoints().toArray();
        int gaps = Math.min(block.advancesMm().size(), Math.max(0, codePoints.length - 1));
        if (gaps == 0) return 0;
        double fontSizeMm = block.style().sizePt() * 25.4d / 72d;
        double horizontalScale = horizontalScalePercent(block) / 100d;
        double extra = 0;
        for (int i = 0; i < gaps; i++) {
            extra += block.advancesMm().get(i) - naturalAdvanceMm(codePoints[i], fontSizeMm) * horizontalScale;
        }
        double average = extra / gaps;
        if (Math.abs(average) < 0.05) return 0;
        average = Math.max(-fontSizeMm * 0.35d, Math.min(fontSizeMm * 2d, average));
        return twips(average);
    }

    private double naturalAdvanceMm(int codePoint, double fontSizeMm) {
        if (Character.isWhitespace(codePoint)) return fontSizeMm * 0.5d;
        if (codePoint <= 0x7f) {
            if (Character.isDigit(codePoint)) return fontSizeMm * 0.5d;
            if (Character.isLetter(codePoint)) return fontSizeMm * 0.55d;
            return fontSizeMm * 0.5d;
        }
        return fontSizeMm;
    }

    private String positionStyle(Rect box, int zOrder) {
        return positionStyle(box, zOrder, 0);
    }

    private String positionStyle(Rect box, int zOrder, double rotationDegrees) {
        return positionStyle(box, zOrder, rotationDegrees, false);
    }

    private String positionStyle(Rect box, int zOrder, double rotationDegrees, boolean preserveNegativeZOrder) {
        return "position:absolute;" +
                "margin-left:" + pt(box.x()) + "pt;" +
                "margin-top:" + pt(box.y()) + "pt;" +
                "width:" + pt(Math.max(0.05, box.width())) + "pt;" +
                "height:" + pt(Math.max(0.05, box.height())) + "pt;" +
                "z-index:" + (preserveNegativeZOrder ? zOrder : Math.max(1, zOrder + 1)) + ";" +
                (Math.abs(rotationDegrees) < 0.01 ? "" : "rotation:" +
                        String.format(Locale.ROOT, "%.3f", rotationDegrees) + ";") +
                "mso-position-horizontal-relative:page;" +
                "mso-position-vertical-relative:page;" +
                "mso-wrap-style:none";
    }

    private int horizontalScalePercent(TextBlock block) {
        double vertical = Math.max(0.01d, block.transform().scaleY());
        double ratio = block.transform().scaleX() / vertical;
        return Math.max(1, Math.min(600, (int) Math.round(ratio * 100d)));
    }

    private String shapeId(String prefix, String sourceId) {
        String safe = sourceId == null ? "" : sourceId.replaceAll("[^A-Za-z0-9_-]", "_");
        return prefix + "-" + safe + "-" + shapeSequence++;
    }

    private String pt(double mm) {
        return String.format(Locale.ROOT, "%.3f", mm * 72d / 25.4d);
    }

    private int twips(double mm) {
        return (int) Math.round(mm * 1440d / 25.4d);
    }

    private int pictureType(String mime) {
        return switch (mime) {
            case "image/jpeg" -> XWPFDocument.PICTURE_TYPE_JPEG;
            case "image/gif" -> XWPFDocument.PICTURE_TYPE_GIF;
            case "image/bmp" -> XWPFDocument.PICTURE_TYPE_BMP;
            case "image/tiff" -> XWPFDocument.PICTURE_TYPE_TIFF;
            default -> XWPFDocument.PICTURE_TYPE_PNG;
        };
    }

    private String attr(String value) {
        return text(value).replace("\n", " ").replace("\r", " ");
    }

    private String text(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private record FixedItem(int zOrder, LineElement line, ImageBlock image, TextBlock text, TableModel table) { }
}
