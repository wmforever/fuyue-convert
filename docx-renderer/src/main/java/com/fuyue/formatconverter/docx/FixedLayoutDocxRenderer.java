package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.*;
import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders fixed-layout OFD pages as editable, absolutely positioned Word shapes.
 * Floating shapes do not participate in Word's reflow, so one source page stays
 * one output page while text remains editable inside text boxes.
 */
final class FixedLayoutDocxRenderer {
    private int shapeSequence = 1;

    /**
     * Adds only page overlays for the semantic renderer. Normal text and
     * recognized tables stay in the Word body; only graphics and text whose
     * transform cannot be represented by a Word run remain floating shapes.
     */
    void renderOverlays(XWPFDocument docx, XWPFParagraph anchor, PageModel page,
                        List<TextBlock> fallbackTexts) throws Exception {
        page.lines().stream().filter(line -> !insideAnyTable(line, page.tables()))
                .sorted(Comparator.comparingInt(LineElement::zOrder))
                .forEach(line -> unchecked(() -> addLine(anchor, line)));
        page.images().stream().sorted(Comparator.comparingInt(ImageBlock::zOrder))
                .forEach(image -> unchecked(() -> addImage(docx, anchor, image)));
        fallbackTexts.stream().sorted(Comparator.comparingInt(TextBlock::zOrder))
                .filter(text -> !text.text().isEmpty())
                .forEach(text -> unchecked(() -> addTextBox(docx, anchor, text)));
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
            else if (item.text() != null && !item.text().text().isEmpty()) addTextBox(docx, anchor, item.text());
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

    private void addTextBox(XWPFDocument docx, XWPFParagraph anchor, TextBlock block) throws Exception {
        double topInsetMm = Math.max(0, block.textOffsetYmm() - block.style().sizePt() * 25.4d / 72d * 0.86d);
        Rect textBox = tolerantTextBox(block);
        int characterSpacing = characterSpacingTwips(block);
        FontStyle font = block.style();
        int horizontalScale = horizontalScalePercent(block);
        int halfPoints = Math.max(2, (int) Math.round(font.sizePt() * 2d));
        int lineTwips = Math.max(20, (int) Math.round(font.sizePt() * 20d));
        String runProperties = "<w:rPr>" +
                "<w:rFonts w:ascii=\"" + attr(font.family()) + "\" w:hAnsi=\"" + attr(font.family()) +
                "\" w:eastAsia=\"" + attr(font.family()) + "\"/>" +
                "<w:sz w:val=\"" + halfPoints + "\"/><w:szCs w:val=\"" + halfPoints + "\"/>" +
                (horizontalScale == 100 ? "" : "<w:w w:val=\"" + horizontalScale + "\"/>") +
                (font.bold() ? "<w:b/><w:bCs/>" : "") +
                (font.italic() ? "<w:i/><w:iCs/>" : "") +
                "<w:color w:val=\"" + font.color().rgbHex() + "\"/>" +
                (characterSpacing == 0 ? "" : "<w:spacing w:val=\"" + characterSpacing + "\"/>") +
                "</w:rPr>";
        String xml = "<v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
                "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "id=\"" + attr(shapeId("text", block.id())) + "\" style=\"" +
                attr(positionStyle(textBox, block.zOrder(), block.transform().rotationDegrees())) +
                "\" filled=\"f\" stroked=\"f\">" +
                "<v:textbox inset=\"" + pt(block.textOffsetXmm()) + "pt," + pt(topInsetMm) +
                "pt,0pt,0pt\" style=\"mso-fit-shape-to-text:false\">" +
                "<w:txbxContent><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"" +
                lineTwips + "\" w:lineRule=\"exact\"/></w:pPr><w:r>" + runProperties +
                "<w:t xml:space=\"preserve\">" + text(block.text()) + "</w:t></w:r></w:p></w:txbxContent>" +
                "</v:textbox></v:shape>";
        appendShape(anchor, xml);
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
        String xml = "<v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "id=\"" + attr(shapeId("image", image.id())) + "\" style=\"" +
                attr(positionStyle(image.box(), image.zOrder())) + "\" filled=\"f\" stroked=\"f\">" +
                "<v:imagedata r:id=\"" + attr(relationId) + "\" title=\"" + attr(image.id()) + "\"/>" +
                "</v:shape>";
        appendShape(anchor, xml);
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
            int halfPoints = Math.max(2, (int) Math.round(font.sizePt() * 2d));
            xml.append("<w:r><w:rPr><w:rFonts w:ascii=\"").append(attr(font.family()))
                    .append("\" w:hAnsi=\"").append(attr(font.family())).append("\" w:eastAsia=\"")
                    .append(attr(font.family())).append("\"/><w:sz w:val=\"").append(halfPoints)
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
        return "position:absolute;" +
                "margin-left:" + pt(box.x()) + "pt;" +
                "margin-top:" + pt(box.y()) + "pt;" +
                "width:" + pt(Math.max(0.05, box.width())) + "pt;" +
                "height:" + pt(Math.max(0.05, box.height())) + "pt;" +
                "z-index:" + Math.max(1, zOrder + 1) + ";" +
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
