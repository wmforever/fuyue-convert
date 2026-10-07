package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocxToTextConverterTest {
    @TempDir Path temp;

    @Test
    void extractsRenderedOcrLinesWithoutAddedParenthesesAndKeepsLiteralPunctuationAndEdits() throws Exception {
        var first = ocrLine("ocr-p1-l1", 20, List.of("记录", "(00973)", "-0054.80"), "记录 (00973) -0054.80");
        var second = ocrLine("ocr-p1-l2", 40, List.of("测试", "策略"), "测试策略");
        var page = new PageModel(1, new Rect(0, 0, 210, 297), List.of(first, second),
                List.of(), List.of(), List.of(), List.of(), List.of());
        Path source = temp.resolve("scan.docx");
        new PoiDocxRenderer().render(new DocumentModel("scan.pdf", "test", 1, List.of(page), List.of()), source);

        assertEquals("记录 (00973) -0054.80\n测试策略\n", extract(source));

        // Edit the same visible run that Word edits; do not use a second text layer.
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(source))) {
            var nodes = doc.getDocument().getDomNode().getOwnerDocument()
                    .getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
            int edits = 0;
            for (int i = 0; i < nodes.getLength(); i++) {
                var node = nodes.item(i);
                if (node.getFirstChild() != null && "-0054.80".equals(node.getFirstChild().getNodeValue())) {
                    node.getFirstChild().setNodeValue("-0068.95");
                    edits++;
                }
            }
            assertEquals(1, edits);
            try (var output = Files.newOutputStream(source)) { doc.write(output); }
        }
        assertEquals("记录 (00973) -0068.95\n测试策略\n", extract(source));
    }

    @Test
    void leavesMixedNativeAndGenericTextboxesOnTheExistingExtractionPath() throws Exception {
        for (String extra : List.of(
                "<w:r><w:t>正文 (原括号) 001</w:t></w:r>",
                "<w:ins><w:r><w:t>修订 002</w:t></w:r></w:ins>",
                textbox("generic-box", "普通框 (003)"),
                textbox("text-ocr-p1-l1-word-0-99", "复制框 (004)"))) {
            Path source = temp.resolve("mixed-" + Math.abs(extra.hashCode()) + ".docx");
            String expected;
            try (XWPFDocument doc = new XWPFDocument()) {
                CTP paragraph = CTP.Factory.parse("<xml-fragment xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                        + " xmlns:v=\"urn:schemas-microsoft-com:vml\">"
                        + textbox("text-ocr-p1-l1-word-0-1", "识别 (005)") + extra + "</xml-fragment>");
                doc.getDocument().getBody().addNewP().set(paragraph);
                try (var out = Files.newOutputStream(source)) { doc.write(out); }
            }
            try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(source))) {
                expected = doc.getParagraphs().get(0).getText().stripTrailing() + "\n";
                if (extra.contains("<w:ins>")) expected += "[修订-插入] 修订 002\n";
            }
            assertEquals(expected, extract(source), "Unfamiliar/mixed anchors must retain all prior content");
        }
    }

    private TextBlock ocrLine(String id, double y, List<String> values, String text) {
        var words = new java.util.ArrayList<TextBlock.OcrWord>();
        for (int i = 0; i < values.size(); i++) {
            words.add(new TextBlock.OcrWord(new Rect(20 + i * 40, y, 30, 5), values.get(i), i == 0 ? .4 : .98));
        }
        return new TextBlock(id, 1, new Rect(20, y, 140, 5), text, y + 5,
                new FontStyle("Arial", 12, false, false, null), 3, 0, 0, List.of(), Transform2D.IDENTITY, words);
    }

    private String textbox(String id, String text) {
        return "<w:r><w:pict><v:rect id=\"" + id + "\"><v:textbox><w:txbxContent><w:p><w:r><w:t>"
                + text + "</w:t></w:r></w:p></w:txbxContent></v:textbox></v:rect></w:pict></w:r>";
    }

    private String extract(Path source) throws Exception {
        Path output = temp.resolve("result.txt");
        new DocxToTextConverter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });
        return Files.readString(output).replace("\r\n", "\n");
    }

    @Test
    void extractsOrdinaryDocumentWithoutOptionalStoryParts() throws Exception {
        Path source = temp.resolve("ordinary.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("普通正文，无批注和脚注");
            try (var output = Files.newOutputStream(source)) { document.write(output); }
        }
        Path output = temp.resolve("ordinary.txt");

        new DocxToTextConverter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertTrue(Files.readString(output).contains("普通正文，无批注和脚注"));
    }

    @Test
    void extractsAllDocumentStoriesTablesTextBoxesCommentsAndRevisionsInDeclaredOrder() throws Exception {
        Path source = temp.resolve("stories.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页眉文字");
            document.createParagraph().createRun().setText("正文之前");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("左单元格");
            table.getRow(0).getCell(1).setText("右单元格");
            document.createParagraph().createRun().setText("正文之后");

            var revision = document.createParagraph().getCTP();
            revision.addNewIns().addNewR().addNewT().setStringValue("插入文字");
            revision.addNewDel().addNewR().addNewDelText().setStringValue("删除文字");

            CTP textBox = CTP.Factory.parse("""
                    <w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                         xmlns:v="urn:schemas-microsoft-com:vml">
                      <w:r><w:pict><v:shape><v:textbox><w:txbxContent>
                        <w:p><w:r><w:t>文本框文字</w:t></w:r></w:p>
                      </w:txbxContent></v:textbox></v:shape></w:pict></w:r>
                    </w:p>
                    """);
            document.getDocument().getBody().addNewP().set(textBox);

            document.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页脚文字");
            var footnote = document.createFootnote();
            footnote.createParagraph().createRun().setText("脚注文字");
            var endnote = document.createEndnote();
            endnote.createParagraph().createRun().setText("尾注文字");
            var comment = document.createComments().createComment(BigInteger.ONE);
            comment.setAuthor("审核人");
            comment.createParagraph().createRun().setText("批注文字");
            try (var output = Files.newOutputStream(source)) { document.write(output); }
        }
        Path output = temp.resolve("stories.txt");

        new DocxToTextConverter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        String text = Files.readString(output).replace("\r\n", "\n");
        assertContainsInOrder(text,
                "[页眉]", "页眉文字", "正文之前", "左单元格\t右单元格", "正文之后",
                "[修订-插入] 插入文字", "[修订-删除] 删除文字", "[文本框] 文本框文字",
                "[页脚]", "页脚文字", "[脚注 ", "脚注文字", "[尾注 ", "尾注文字",
                "[批注 1 / 审核人] 批注文字");
    }

    private void assertContainsInOrder(String value, String... expected) {
        int position = 0;
        for (String item : expected) {
            int found = value.indexOf(item, position);
            assertTrue(found >= position, "Missing or out of order: " + item + "\n" + value);
            position = found + item.length();
        }
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.size(source), source);
    }
}
