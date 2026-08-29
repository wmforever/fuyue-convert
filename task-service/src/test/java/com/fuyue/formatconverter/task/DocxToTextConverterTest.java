package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxToTextConverterTest {
    @TempDir Path temp;

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
