package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Independent boundary cases for the conservative paragraph reconstruction. */
class PdfParagraphBoundaryTest {
    private static final FontStyle BODY = new FontStyle("Arial", 12, false, false, ColorValue.BLACK);
    private static final double LEADING = 18d * 25.4d / 72d;
    @TempDir Path temp;

    @Test void keepsAlphabeticAndRomanListItemsAsIndependentParagraphs() {
        for (List<String> markers : List.of(List.of("a)", "b)"), List.of("A.", "B."),
                List.of("(a)", "(b)"), List.of("i)", "ii)"), List.of("IV.", "V."))) {
            PageModel page = page(List.of(
                    line(0, 160, markers.get(0) + " Review every delivery record and keep the approved copy."),
                    line(1, 150, markers.get(1) + " Confirm all invoice details before releasing the payment.")), List.of());

            PageModel result = new PdfParagraphReconstructor().reconstruct(page);

            assertEquals(2, result.paragraphs().size(), "编号 " + markers + " 不得被串成同一段");
            assertTrue(result.paragraphs().stream().allMatch(paragraph -> paragraph.flow() == null));
        }
    }

    @Test void stopsAtAnExplicitHorizontalSectionSeparator() {
        double separatorY = 34.5d;
        LineElement separator = new LineElement("section-rule", 1,
                new Point(20, separatorY), new Point(180, separatorY), 0.2, ColorValue.BLACK, 2);
        PageModel page = page(List.of(
                line(0, 160, "A complete section finishes here and uses the full available text width."),
                line(1, 155, "The next section begins below a visible horizontal dividing rule.")), List.of(separator));

        PageModel result = new PdfParagraphReconstructor().reconstruct(page);

        assertEquals(2, result.paragraphs().size(), "横向分隔线是明确段落边界，不能将两侧文字合并");
        assertTrue(result.paragraphs().stream().allMatch(paragraph -> paragraph.flow() == null));
    }

    @Test void recognizesAListMarkerSeparatedFromItsBodyByPdfCoordinates() {
        PageModel page = page(List.of(
                splitListLine(0, "a)", "Review every delivery record and keep the approved copy."),
                splitListLine(1, "b)", "Confirm all invoice details before releasing the payment.")), List.of());

        PageModel result = new PdfParagraphReconstructor().reconstruct(page);

        assertEquals(2, result.paragraphs().size(),
                "PDF可用坐标而非空格字形分隔编号；独立编号文本块仍须阻止列表误合并");
        assertTrue(result.paragraphs().stream().allMatch(paragraph -> paragraph.flow() == null));
    }

    @Test void keepsAShortEndingSeparateFromTheNextUnindentedParagraph() {
        PageModel page = page(List.of(
                line(0, 160, "This full source line continues into the shorter final line of its paragraph"),
                line(1, 85, "and the paragraph ends here."),
                line(2, 160, "Documentation for a separate paragraph starts without a first line indent.")), List.of());

        PageModel result = new PdfParagraphReconstructor().reconstruct(page);

        assertEquals(2, result.paragraphs().size());
        assertEquals(2, result.paragraphs().get(0).flow().sourceLineCount());
        assertNull(result.paragraphs().get(1).flow());
    }

    @Test void keepsRepeatedLongRecordsWithoutSentenceEndingIndependent() {
        List<ParagraphModel> records = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> line(index, 160 - index,
                        "Record customer delivery status complete batch 20260926 build 1.0 item %03d"
                                .formatted(index + 1)))
                .toList();

        PageModel result = new PdfParagraphReconstructor().reconstruct(page(records, List.of()));

        assertEquals(records, result.paragraphs(),
                "等宽且等行距的独立记录不能仅凭几何位置合成正文；版本号中的小数点不是句末标点");
    }

    @Test void allowsAnUnfinishedFinalLineWhenTheGroupAlreadyContainsACompleteSentence() {
        PageModel page = page(List.of(
                line(0, 160, "The delivery record is complete. The following explanation continues with"),
                line(1, 155, "additional details that may continue on the next source page")), List.of());

        PageModel result = new PdfParagraphReconstructor().reconstruct(page);

        assertEquals(1, result.paragraphs().size(),
                "正文已有完整句子即可提供句读证据，不应要求每页最后一个视觉行也必须以句号结束");
        assertNotNull(result.paragraphs().get(0).flow());
        assertEquals(2, result.paragraphs().get(0).flow().sourceLineCount());
    }

    @Test void keepsDifferentlySizedCenteredLinesAsSeparateCenteredParagraphs() throws Exception {
        List<ParagraphModel> centered = List.of(
                centeredLine(0, 24, 162,
                        "This centered first line of a notice contains a complete sentence."),
                centeredLine(1, 20, 170,
                        "This centered second line of the notice also contains a complete sentence."));

        PageModel result = new PdfParagraphReconstructor().reconstruct(page(centered, List.of()));

        assertEquals(centered, result.paragraphs(),
                "宽度不同但中心对齐的声明不得被改成左对齐且首行缩进的正文段落");
        assertTrue(result.paragraphs().stream().allMatch(paragraph -> paragraph.flow() == null));
        Path output = temp.resolve("centered-notice.docx");
        new PoiDocxRenderer().render(new DocumentModel("notice.pdf", "boundary-test", 1,
                List.of(result), List.of()), output);
        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            var paragraphs = word.getParagraphs().stream().filter(paragraph -> !paragraph.getText().isBlank()).toList();
            assertEquals(centered.stream().map(paragraph -> paragraph.runs().get(0).text()).toList(),
                    paragraphs.stream().map(paragraph -> paragraph.getText()).toList());
            assertTrue(paragraphs.stream().allMatch(paragraph -> paragraph.getAlignment()
                    == org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER));
        }
    }

    @Test void stillReconstructsFullBodyLinesThatWereMerelyClassifiedAsCentered() {
        PageModel page = page(List.of(
                centeredLine(0, 20, 170,
                        "The complete delivery record is available. The following explanation continues with"),
                centeredLine(1, 20, 169,
                        "additional details about the agreed scope and the responsible service provider.")), List.of());

        PageModel result = new PdfParagraphReconstructor().reconstruct(page);

        assertEquals(1, result.paragraphs().size(),
                "对称页边距会使普通正文满行被判为CENTER，不能仅凭该标签拒绝段落重建");
        assertNotNull(result.paragraphs().get(0).flow());
        assertEquals(2, result.paragraphs().get(0).flow().sourceLineCount());
    }

    @Test void preservesAnExistingHyphenWhenRejoiningEditableWordText() throws Exception {
        String first = "The agreement includes support for an existing state-of-";
        String second = "the-art system and preserves the agreed version identifiers.";
        PageModel page = new PdfParagraphReconstructor().reconstruct(page(List.of(
                line(0, 160, first), line(1, 145, second)), List.of()));
        assertEquals(1, page.paragraphs().size());
        Path output = temp.resolve("hyphen-preserved.docx");

        new PoiDocxRenderer().render(new DocumentModel("hyphen.pdf", "boundary-test", 1,
                List.of(page), List.of()), output);

        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            assertEquals(List.of(first + second), word.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText()).filter(text -> !text.isBlank()).toList(),
                    "不猜测删除原PDF中的连字符，也不在连字符后插入人工空格");
            assertFalse(word.getDocument().xmlText().contains("<w:br"));
            assertTrue(word.getAllPictures().isEmpty());
        }
    }

    private static ParagraphModel line(int index, double width, String text) {
        double y = 30 + index * LEADING;
        Rect box = new Rect(20, y, width, 3d);
        TextBlock run = new TextBlock("line-" + index, 1, box, text, y + 2.8d, BODY, index);
        return new ParagraphModel(box, List.of(run), ParagraphModel.Alignment.LEFT, 0);
    }

    private static ParagraphModel splitListLine(int index, String marker, String text) {
        double y = 30 + index * LEADING;
        TextBlock prefix = new TextBlock("marker-" + index, 1, new Rect(20, y, 4, 3),
                marker, y + 2.8d, BODY, index * 2);
        TextBlock body = new TextBlock("body-" + index, 1, new Rect(26, y, 154, 3),
                text, y + 2.8d, BODY, index * 2 + 1);
        return new ParagraphModel(prefix.box().union(body.box()), List.of(prefix, body),
                ParagraphModel.Alignment.LEFT, 0);
    }

    private static ParagraphModel centeredLine(int index, double x, double width, String text) {
        double y = 30 + index * LEADING;
        Rect box = new Rect(x, y, width, 3);
        TextBlock run = new TextBlock("centered-" + index, 1, box, text, y + 2.8, BODY, index);
        return new ParagraphModel(box, List.of(run), ParagraphModel.Alignment.CENTER, 0);
    }

    private static PageModel page(List<ParagraphModel> lines, List<LineElement> graphics) {
        return new PageModel(1, new Rect(0, 0, 210, 297),
                lines.stream().flatMap(line -> line.runs().stream()).toList(), graphics,
                List.of(), lines, List.of(), List.of());
    }
}
