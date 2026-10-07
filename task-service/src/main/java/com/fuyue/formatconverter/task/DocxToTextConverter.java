package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.regex.Pattern;

public final class DocxToTextConverter implements FileConverter {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String VML = "urn:schemas-microsoft-com:vml";
    private static final Pattern OCR_WORD_ID = Pattern.compile("text-(ocr-p[0-9]+-l[0-9]+)-word-([0-9]+)-[0-9]+");
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.DOCX, DocumentFormat.TXT,
            "提取 Word 文字，包括正文、表格、文本框、页眉页脚、脚注尾注、批注和修订。",
            QualityLevel.BETA, ConversionStrategy.EXTRACTION, List.of(),
            List.of("不保留版式和样式", "页眉放在正文前，页脚、脚注尾注和批注放在正文后，并标明来源；修订在对应段落后标明"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 35);
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(input.path()))) {
            document.getHeaderList().forEach(header -> appendStory(text, "页眉", header.getBodyElements()));
            appendElements(text, document.getBodyElements());
            document.getFooterList().forEach(footer -> appendStory(text, "页脚", footer.getBodyElements()));
            var footnotes = document.getFootnotes();
            if (footnotes != null) {
                footnotes.stream().filter(note -> note.getId() != null && note.getId().signum() > 0)
                        .forEach(note -> appendStory(text, "脚注 " + note.getId(), note.getBodyElements()));
            }
            var endnotes = document.getEndnotes();
            if (endnotes != null) {
                endnotes.stream().filter(note -> note.getId() != null && note.getId().signum() > 0)
                        .forEach(note -> appendStory(text, "尾注 " + note.getId(), note.getBodyElements()));
            }
            var comments = document.getComments();
            if (comments != null) {
                for (var comment : comments) {
                    String value = normalizeLine(comment.getText());
                    if (!value.isBlank()) appendLine(text, "[批注 " + comment.getId() + " / "
                            + safeLabel(comment.getAuthor()) + "] " + value);
                }
            }
        }
        byte[] utf8 = text.toString().getBytes(StandardCharsets.UTF_8);
        if (utf8.length > limits.maxExpandedBytes()) {
            throw new java.io.IOException("DOCX 提取文本超过限制：" + utf8.length + " > " + limits.maxExpandedBytes());
        }
        progress.update(TaskStage.RENDERING, 80);
        Files.write(outputPath, utf8);
        ConversionGuards.requireOutputFile(outputPath, limits, "DOCX 转 TXT");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.docx$", ".txt"), null, List.of());
    }

    private static void appendStory(StringBuilder out, String label, List<IBodyElement> elements) {
        StringBuilder story = new StringBuilder();
        appendElements(story, elements);
        if (story.toString().isBlank()) return;
        appendLine(out, "[" + label + "]");
        out.append(story);
    }

    private static void appendElements(StringBuilder out, List<IBodyElement> elements) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) appendParagraph(out, paragraph);
            else if (element instanceof XWPFTable table) appendTable(out, table);
            else if (element instanceof XWPFSDT control) appendLine(out, control.getContent().getText());
        }
    }

    private static void appendParagraph(StringBuilder out, XWPFParagraph paragraph) {
        List<String> ocrLines = ocrAnchorLines(paragraph.getCTP().getDomNode());
        if (ocrLines != null) {
            ocrLines.forEach(line -> appendLine(out, line));
            return;
        }
        String value = paragraph.getText();
        appendLine(out, value);
        for (String textBox : descendantContainerTexts(paragraph.getCTP().getDomNode(), "txbxContent")) {
            if (!textBox.isBlank() && (value == null || !normalizeLine(value).contains(normalizeLine(textBox)))) {
                appendLine(out, "[文本框] " + textBox);
            }
        }
        appendRevisionTexts(out, paragraph.getCTP().getDomNode(), "ins", "修订-插入");
        appendRevisionTexts(out, paragraph.getCTP().getDomNode(), "del", "修订-删除");
        appendRevisionTexts(out, paragraph.getCTP().getDomNode(), "moveFrom", "修订-移出");
        appendRevisionTexts(out, paragraph.getCTP().getDomNode(), "moveTo", "修订-移入");
    }

    /** POI wraps picture textbox text in parentheses. Read our standalone OCR anchors
     * from their editable runs instead, keeping real punctuation and original line IDs.
     * Mixed, revised or unfamiliar content retains the general extraction path. */
    private static List<String> ocrAnchorLines(Node root) {
        List<Element> boxes = descendants(root, "txbxContent");
        if (boxes.isEmpty()) return null;
        for (String name : List.of("ins", "del", "moveFrom", "moveTo", "instrText", "sym", "fldChar", "sdt")) {
            if (!descendants(root, name).isEmpty()) return null;
        }
        var wordIds = new HashSet<String>();
        var lineIds = new HashSet<String>();
        List<String> lines = new ArrayList<>();
        String currentLine = null;
        StringBuilder line = new StringBuilder();
        for (Element box : boxes) {
            Node parent = box.getParentNode();
            Node shape = parent == null ? null : parent.getParentNode();
            if (!WORD.equals(box.getNamespaceURI()) || !(shape instanceof Element rect)
                    || !VML.equals(rect.getNamespaceURI()) || !"rect".equals(rect.getLocalName())
                    || !VML.equals(parent.getNamespaceURI()) || !"textbox".equals(parent.getLocalName())
                    || box.getElementsByTagNameNS(WORD, "p").getLength() != 1
                    || !descendants(box, "tbl").isEmpty()) return null;
            var id = OCR_WORD_ID.matcher(rect.getAttribute("id"));
            if (!id.matches() || !wordIds.add(id.group(1) + "-" + id.group(2))) return null;
            if (!id.group(1).equals(currentLine)) {
                if (!lineIds.add(id.group(1))) return null;
                if (currentLine != null) lines.add(line.toString());
                line.setLength(0);
                currentLine = id.group(1);
            }
            collectText(box, line);
        }
        // Never bypass body text outside the recognized boxes, including tabs/breaks.
        for (String name : List.of("t", "delText", "tab", "br", "cr")) {
            for (Element text : descendants(root, name)) {
                Node ancestor = text.getParentNode();
                while (ancestor != null && !boxes.contains(ancestor)) ancestor = ancestor.getParentNode();
                if (ancestor == null) return null;
            }
        }
        lines.add(line.toString());
        return lines;
    }

    private static void appendTable(StringBuilder out, XWPFTable table) {
        table.getRows().forEach(row -> {
            List<String> cells = new ArrayList<>();
            row.getTableCells().forEach(cell -> {
                StringBuilder value = new StringBuilder();
                appendElements(value, cell.getBodyElements());
                cells.add(normalizeLine(value.toString()));
            });
            appendLine(out, String.join("\t", cells));
        });
    }

    private static void appendRevisionTexts(StringBuilder out, Node root, String elementName, String label) {
        for (Element element : descendants(root, elementName)) {
            String value = descendantText(element);
            if (!value.isBlank()) appendLine(out, "[" + label + "] " + value);
        }
    }

    private static List<String> descendantContainerTexts(Node root, String elementName) {
        return descendants(root, elementName).stream().map(DocxToTextConverter::descendantText)
                .filter(value -> !value.isBlank()).toList();
    }

    private static List<Element> descendants(Node root, String localName) {
        List<Element> result = new ArrayList<>();
        collectElements(root, localName, result);
        return result;
    }

    private static void collectElements(Node node, String localName, List<Element> result) {
        if (node instanceof Element element && localName.equals(element.getLocalName())) result.add(element);
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectElements(child, localName, result);
        }
    }

    private static String descendantText(Node root) {
        StringBuilder value = new StringBuilder();
        collectText(root, value);
        return normalizeLine(value.toString());
    }

    private static void collectText(Node node, StringBuilder out) {
        if (node instanceof Element element) {
            String local = element.getLocalName();
            if ("t".equals(local) || "delText".equals(local)) {
                for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                        out.append(child.getNodeValue());
                    }
                }
                return;
            }
            if ("tab".equals(local)) out.append('\t');
            else if ("br".equals(local) || "cr".equals(local)) out.append(' ');
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectText(child, out);
        }
    }

    private static void appendLine(StringBuilder out, String value) {
        String line = value == null ? "" : value.stripTrailing();
        if (!line.isBlank()) out.append(line).append(System.lineSeparator());
    }

    private static String normalizeLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[ \\t]+", " ").strip();
    }

    private static String safeLabel(String value) {
        return value == null || value.isBlank() ? "未知作者" : value.replaceAll("[\\r\\n\\t]", " ").strip();
    }
}
