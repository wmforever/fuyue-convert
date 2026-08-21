package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.*;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class PoiDocxRenderer implements DocxRenderer {
    private static final double PAGE_EDGE_MM = 0d;

    @Override
    public void render(DocumentModel document, Path output) throws DocxRenderException {
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            try (XWPFDocument docx = new XWPFDocument()) {
                if (document.pages().isEmpty()) throw new IOException("文档没有可渲染页面");
                List<PageModel> pages = document.pages();
                FixedLayoutDocxRenderer overlays = new FixedLayoutDocxRenderer();
                for (int i = 0; i < pages.size(); i++) {
                    PageModel page = pages.get(i);
                    XWPFParagraph anchor = docx.createParagraph();
                    configureMarker(anchor);
                    if (i > 0 && samePageGeometry(pages.get(i - 1).physicalBox(), page.physicalBox())) {
                        CTPPr anchorProperties = anchor.getCTP().isSetPPr()
                                ? anchor.getCTP().getPPr() : anchor.getCTP().addNewPPr();
                        CTOnOff pageBreakBefore = anchorProperties.isSetPageBreakBefore()
                                ? anchorProperties.getPageBreakBefore() : anchorProperties.addNewPageBreakBefore();
                        pageBreakBefore.setVal(true);
                    }
                    List<ParagraphModel> semanticParagraphs = page.paragraphs().isEmpty()
                            ? page.textBlocks().stream().map(block -> new ParagraphModel(
                                    block.box(), List.of(block), ParagraphModel.Alignment.LEFT, 0)).toList()
                            : page.paragraphs();
                    List<ParagraphModel> fixedParagraphs = semanticParagraphs.stream()
                            .filter(paragraph -> requiresFixedPosition(paragraph)
                                    || overlapsComplexLayout(paragraph, semanticParagraphs))
                            .toList();
                    List<TextBlock> fallbackTexts = fixedParagraphs.stream()
                            .flatMap(paragraph -> paragraph.runs().stream()).toList();
                    overlays.renderOverlays(docx, anchor, page, fallbackTexts);
                    boolean geometryChanges = i < pages.size() - 1
                            && !samePageGeometry(page.physicalBox(), pages.get(i + 1).physicalBox());
                    XWPFParagraph sectionCarrier = renderPage(docx, page, semanticParagraphs,
                            fixedParagraphs, anchor, geometryChanges);
                    CTPPr boundaryProperties = null;
                    if (i < pages.size() - 1) {
                        boundaryProperties = sectionCarrier.getCTP().isSetPPr()
                                ? sectionCarrier.getCTP().getPPr() : sectionCarrier.getCTP().addNewPPr();
                        if (needsSectionBreakReserve(page, semanticParagraphs, fixedParagraphs)) {
                            reserveSectionBreakSpace(boundaryProperties);
                        }
                    }
                    if (geometryChanges) {
                        configureSection(boundaryProperties.addNewSectPr(), page.physicalBox(), true);
                    } else if (i == pages.size() - 1) {
                        CTSectPr finalSection = docx.getDocument().getBody().isSetSectPr()
                                ? docx.getDocument().getBody().getSectPr()
                                : docx.getDocument().getBody().addNewSectPr();
                        configureSection(finalSection, page.physicalBox(), false);
                    }
                }
                DocxFontSupport.embedBundledCjkFont(docx, document);
                try (var stream = Files.newOutputStream(output)) { docx.write(stream); }
            }
        } catch (Exception e) {
            throw new DocxRenderException("DOCX 生成失败", e);
        }
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
        BigInteger value = BigInteger.valueOf(twips(PAGE_EDGE_MM));
        margin.setTop(value); margin.setBottom(value); margin.setLeft(value); margin.setRight(value);
        margin.setHeader(BigInteger.ZERO); margin.setFooter(BigInteger.ZERO); margin.setGutter(BigInteger.ZERO);
    }

    private XWPFParagraph renderPage(XWPFDocument docx, PageModel page,
                            List<ParagraphModel> semanticParagraphs,
                            List<ParagraphModel> fixedParagraphs,
                            XWPFParagraph anchor, boolean needsSectionCarrier) throws Exception {
        List<PositionedContent> content = new ArrayList<>();
        for (ParagraphModel paragraph : semanticParagraphs) {
            if (!fixedParagraphs.contains(paragraph)) {
                content.add(new PositionedContent(paragraph.box().y(), paragraph, null));
            }
        }
        for (TableModel table : page.tables()) content.add(new PositionedContent(table.box().y(), null, table));
        content.sort(Comparator.comparingDouble(PositionedContent::y));

        double previousBottom = page.physicalBox().y();
        XWPFParagraph lastParagraph = anchor;
        boolean tableLast = false;
        for (PositionedContent item : content) {
            if (item.paragraph() != null) {
                lastParagraph = docx.createParagraph();
                renderParagraph(lastParagraph, item.paragraph(), page.physicalBox(), previousBottom);
                previousBottom = item.paragraph().box().y() + paragraphLineHeightMm(item.paragraph());
                tableLast = false;
            } else if (item.table() != null) {
                addVerticalSpacer(docx, Math.max(0, item.table().box().y() - previousBottom));
                renderTable(docx, item.table(), page.physicalBox());
                previousBottom = item.table().box().bottom();
                tableLast = true;
            }
        }
        if (tableLast && needsSectionCarrier) {
            lastParagraph = docx.createParagraph();
            configureMarker(lastParagraph);
        }
        return lastParagraph;
    }

    private void renderParagraph(XWPFParagraph target, ParagraphModel paragraph, Rect page, double previousBottom) {
        target.setAlignment(alignment(paragraph.alignment()));
        target.setSpacingAfter(0);
        target.setSpacingBefore(Math.max(0, twips(paragraph.box().y() - previousBottom)));
        setExactLineHeight(target, paragraphLineHeightMm(paragraph));
        int leftIndent = Math.max(0, twips(paragraph.box().x() - page.x() - PAGE_EDGE_MM));
        int rightIndent = Math.max(0, twips(page.right() - PAGE_EDGE_MM - paragraph.box().right()));
        switch (paragraph.alignment()) {
            case CENTER -> { target.setIndentationLeft(0); target.setIndentationRight(0); }
            case RIGHT -> { target.setIndentationLeft(0); target.setIndentationRight(rightIndent); }
            case JUSTIFY -> { target.setIndentationLeft(leftIndent); target.setIndentationRight(rightIndent); }
            default -> { target.setIndentationLeft(leftIndent); target.setIndentationRight(0); }
        }
        List<TextBlock> runs = paragraph.runs().stream()
                .sorted(Comparator.comparingDouble(block -> block.box().x())).toList();
        TextBlock previous = null;
        for (TextBlock block : runs) {
            if (previous != null) appendVisualGap(target, previous, block);
            appendRun(target, block);
            previous = block;
        }
    }

    private void appendRun(XWPFParagraph paragraph, TextBlock block) {
        XWPFRun run = paragraph.createRun();
        FontStyle style = block.style();
        String family = DocxFontSupport.familyFor(block);
        run.setText(block.text());
        run.setFontFamily(family);
        run.setFontSize(Math.max(1d, style.sizePt()));
        run.setBold(style.bold());
        run.setItalic(style.italic());
        run.setColor(style.color().rgbHex());
        CTRPr properties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = properties.sizeOfRFontsArray() > 0 ? properties.getRFontsArray(0) : properties.addNewRFonts();
        fonts.setAscii(family); fonts.setHAnsi(family); fonts.setEastAsia(family);
        int horizontalScale = horizontalScalePercent(block);
        if (horizontalScale != 100) properties.addNewW().setVal(BigInteger.valueOf(horizontalScale));
        int characterSpacing = characterSpacingTwips(block);
        if (characterSpacing != 0) properties.addNewSpacing().setVal(BigInteger.valueOf(characterSpacing));
    }

    private void renderTable(XWPFDocument docx, TableModel model, Rect page) {
        XWPFTable table = docx.createTable(model.rowCount(), model.columnCount());
        table.setWidth(twips(model.box().width()));
        table.setTableAlignment(TableRowAlign.LEFT);
        table.setCellMargins(0, 0, 0, 0);
        CTTblPr tableProperties = table.getCTTbl().getTblPr();
        CTTblWidth indent = tableProperties.isSetTblInd()
                ? tableProperties.getTblInd() : tableProperties.addNewTblInd();
        indent.setType(STTblWidth.DXA);
        indent.setW(BigInteger.valueOf(Math.max(0, twips(model.box().x() - page.x()))));
        CTTblLayoutType layout = tableProperties.isSetTblLayout() ? tableProperties.getTblLayout() : tableProperties.addNewTblLayout();
        layout.setType(STTblLayoutType.FIXED);
        CTTblGrid grid = table.getCTTbl().getTblGrid() != null ? table.getCTTbl().getTblGrid() : table.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int c = 0; c < model.columnCount(); c++) {
            grid.addNewGridCol().setW(BigInteger.valueOf(twips(model.xGrid().get(c + 1) - model.xGrid().get(c))));
        }
        for (int r = 0; r < model.rowCount(); r++) {
            XWPFTableRow row = table.getRow(r);
            row.setHeight(Math.max(1, twips(model.yGrid().get(r + 1) - model.yGrid().get(r))));
            row.setHeightRule(TableRowHeightRule.AT_LEAST);
            for (int c = 0; c < model.columnCount(); c++) {
                row.getCell(c).setWidth(Integer.toString(twips(model.xGrid().get(c + 1) - model.xGrid().get(c))));
                clearCell(row.getCell(c));
            }
        }

        for (CellModel cell : model.cells()) {
            XWPFTableCell target = table.getRow(cell.row()).getCell(cell.column());
            fillCell(target, cell);
        }

        for (int r = 0; r < model.rowCount(); r++) {
            final int rowIndex = r;
            model.cells().stream().filter(c -> rowIndex >= c.row() && rowIndex < c.row() + c.rowSpan() && c.columnSpan() > 1)
                    .sorted(Comparator.comparingInt(CellModel::column).reversed())
                    .forEach(cell -> applyGridSpan(table.getRow(rowIndex), cell.column(), cell.columnSpan()));
        }
        for (CellModel cell : model.cells()) {
            if (cell.rowSpan() > 1) {
                for (int r = cell.row(); r < cell.row() + cell.rowSpan(); r++) {
                    XWPFTableCell target = logicalCell(table.getRow(r), cell.column());
                    CTTcPr tcPr = properties(target);
                    CTVMerge merge = tcPr.isSetVMerge() ? tcPr.getVMerge() : tcPr.addNewVMerge();
                    merge.setVal(r == cell.row() ? STMerge.RESTART : STMerge.CONTINUE);
                }
            }
        }
    }

    private void fillCell(XWPFTableCell target, CellModel model) {
        target.setVerticalAlignment(switch (model.verticalAlignment()) {
            case TOP -> XWPFTableCell.XWPFVertAlign.TOP;
            case CENTER -> XWPFTableCell.XWPFVertAlign.CENTER;
            case BOTTOM -> XWPFTableCell.XWPFVertAlign.BOTTOM;
        });
        if (model.fill() != null) target.setColor(model.fill().rgbHex());
        setBorders(target, model);
        clearCell(target);
        if (model.paragraphs().isEmpty()) return;
        for (int i = 0; i < model.paragraphs().size(); i++) {
            XWPFParagraph paragraph = i == 0 ? target.getParagraphs().get(0) : target.addParagraph();
            paragraph.setAlignment(alignment(model.horizontalAlignment()));
            paragraph.setSpacingBefore(0); paragraph.setSpacingAfter(0);
            for (TextBlock block : model.paragraphs().get(i).runs()) appendRun(paragraph, block);
        }
    }

    private void clearCell(XWPFTableCell cell) {
        while (cell.getParagraphs().size() > 1) cell.removeParagraph(cell.getParagraphs().size() - 1);
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) paragraph.removeRun(i);
        paragraph.setSpacingBefore(0); paragraph.setSpacingAfter(0);
    }

    private void applyGridSpan(XWPFTableRow row, int logicalColumn, int span) {
        XWPFTableCell anchor = logicalCell(row, logicalColumn);
        CTTcPr tcPr = properties(anchor);
        CTDecimalNumber gridSpan = tcPr.isSetGridSpan() ? tcPr.getGridSpan() : tcPr.addNewGridSpan();
        gridSpan.setVal(BigInteger.valueOf(span));
        int physical = row.getTableCells().indexOf(anchor);
        for (int i = 1; i < span && physical + 1 < row.getTableCells().size(); i++) {
            row.getCtRow().removeTc(physical + 1);
        }
    }

    private XWPFTableCell logicalCell(XWPFTableRow row, int logicalColumn) {
        int position = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            int span = 1;
            CTTcPr tcPr = cell.getCTTc().getTcPr();
            if (tcPr != null && tcPr.isSetGridSpan()) span = tcPr.getGridSpan().getVal().intValue();
            if (logicalColumn >= position && logicalColumn < position + span) return cell;
            position += span;
        }
        throw new IllegalArgumentException("Logical table column is outside row: " + logicalColumn);
    }

    private void setBorders(XWPFTableCell cell, CellModel model) {
        CTTcBorders borders = properties(cell).isSetTcBorders() ? properties(cell).getTcBorders() : properties(cell).addNewTcBorders();
        setBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop(), model.top());
        setBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight(), model.right());
        setBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom(), model.bottom());
        setBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft(), model.left());
    }

    private void setBorder(CTBorder target, BorderStyle source) {
        target.setVal(source.pattern() == BorderStyle.Pattern.NONE ? STBorder.NIL : STBorder.SINGLE);
        target.setColor(source.color().rgbHex());
        target.setSz(BigInteger.valueOf(Math.max(2, Math.round(source.widthMm() * 72d / 25.4d * 8d))));
    }

    private CTTcPr properties(XWPFTableCell cell) {
        return cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
    }

    private void configureMarker(XWPFParagraph paragraph) {
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        setExactLineHeight(paragraph, 0.02d);
    }

    /**
     * Word reserves a small amount of layout space for a next-page section
     * mark even when the paragraph already fits the source page exactly. OFD
     * footers commonly sit within the final millimetre, so reclaim a tiny part
     * of the last paragraph's leading gap instead of allowing Word to create a
     * blank overflow page.
     */
    private void reserveSectionBreakSpace(CTPPr pPr) {
        if (!pPr.isSetSpacing() || pPr.getSpacing().getBefore() == null) return;
        BigInteger before = new BigInteger(pPr.getSpacing().getBefore().toString());
        BigInteger reserve = BigInteger.valueOf(twips(2d));
        pPr.getSpacing().setBefore(before.subtract(reserve).max(BigInteger.ZERO));
    }

    private void addVerticalSpacer(XWPFDocument docx, double heightMm) {
        XWPFParagraph spacer = docx.createParagraph();
        spacer.setSpacingBefore(twips(heightMm));
        spacer.setSpacingAfter(0);
        setExactLineHeight(spacer, 0.02d);
    }

    private void setExactLineHeight(XWPFParagraph paragraph, double heightMm) {
        CTPPr pPr = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTSpacing spacing = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(Math.max(1, twips(heightMm))));
        spacing.setLineRule(STLineSpacingRule.EXACT);
        CTOnOff snap = pPr.isSetSnapToGrid() ? pPr.getSnapToGrid() : pPr.addNewSnapToGrid();
        snap.setVal(false);
    }

    private double paragraphLineHeightMm(ParagraphModel paragraph) {
        // The OFD boundary already describes the source line box. Replacing it
        // with Word's nominal font height adds a small amount on every visual
        // line; over a dense page those fractions accumulate and push the
        // footer onto an extra page.
        double fontHeight = paragraph.runs().stream().mapToDouble(run -> run.style().sizePt())
                .max().orElse(1d) * 25.4d / 72d;
        return Math.max(0.5d, Math.max(paragraph.box().height(), fontHeight));
    }

    private boolean needsSectionBreakReserve(PageModel page, List<ParagraphModel> paragraphs,
                                             List<ParagraphModel> fixedParagraphs) {
        double flowBottom = paragraphs.stream().filter(paragraph -> !fixedParagraphs.contains(paragraph))
                .mapToDouble(paragraph -> paragraph.box().y() + paragraphLineHeightMm(paragraph))
                .max().orElse(page.physicalBox().y());
        flowBottom = Math.max(flowBottom, page.tables().stream().mapToDouble(table -> table.box().bottom())
                .max().orElse(page.physicalBox().y()));
        return page.physicalBox().bottom() - flowBottom < 2d;
    }

    private void appendVisualGap(XWPFParagraph paragraph, TextBlock previous, TextBlock current) {
        double gapMm = current.box().x() - previous.box().right();
        if (gapMm < 0.6d || current.text().startsWith(" ") || previous.text().endsWith(" ")) return;
        double spaceMm = Math.max(0.7d, current.style().sizePt() * 25.4d / 72d * 0.5d);
        int spaces = Math.max(1, Math.min(32, (int) Math.round(gapMm / spaceMm)));
        XWPFRun gap = paragraph.createRun();
        gap.setText(" ".repeat(spaces));
        gap.setFontFamily(current.style().family());
        gap.setFontSize(Math.max(1d, current.style().sizePt()));
    }

    private boolean requiresFixedPosition(ParagraphModel paragraph) {
        return paragraph.runs().stream().anyMatch(block ->
                Math.abs(block.transform().rotationDegrees()) > 0.5d
                        || block.transform().hasSkew(0.02d));
    }

    private boolean overlapsComplexLayout(ParagraphModel paragraph,
                                          List<ParagraphModel> allParagraphs) {
        return allParagraphs.stream().filter(other -> other != paragraph).anyMatch(other -> {
            double verticalOverlap = Math.min(paragraph.box().bottom(), other.box().bottom())
                    - Math.max(paragraph.box().y(), other.box().y());
            if (verticalOverlap <= Math.min(paragraph.box().height(), other.box().height()) * 0.2d) {
                return false;
            }
            double horizontalOverlap = Math.min(paragraph.box().right(), other.box().right())
                    - Math.max(paragraph.box().x(), other.box().x());
            return horizontalOverlap <= Math.min(paragraph.box().width(), other.box().width()) * 0.2d;
        });
    }

    private boolean samePageGeometry(Rect first, Rect second) {
        return Math.abs(first.width() - second.width()) < 0.1d
                && Math.abs(first.height() - second.height()) < 0.1d;
    }

    private int horizontalScalePercent(TextBlock block) {
        double vertical = Math.max(0.01d, block.transform().scaleY());
        double ratio = block.transform().scaleX() / vertical;
        return Math.max(1, Math.min(600, (int) Math.round(ratio * 100d)));
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
        if (Math.abs(average) < 0.05d) return 0;
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
    private ParagraphAlignment alignment(ParagraphModel.Alignment value) {
        return switch (value) {
            case CENTER -> ParagraphAlignment.CENTER;
            case RIGHT -> ParagraphAlignment.RIGHT;
            case JUSTIFY -> ParagraphAlignment.BOTH;
            default -> ParagraphAlignment.LEFT;
        };
    }
    private int twips(double mm) { return (int) Math.round(mm * 1440d / 25.4d); }
    private record PositionedContent(double y, ParagraphModel paragraph, TableModel table) {}
}
