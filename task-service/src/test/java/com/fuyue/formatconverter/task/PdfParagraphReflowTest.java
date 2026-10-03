package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfParagraphReflowTest {
    static final String FIRST_PARAGRAPH = "The supplier shall provide the agreed services with reasonable care and skill. "
            + "Both parties shall keep complete records of the work and promptly report any material changes "
            + "that may affect delivery.";
    static final String SECOND_PARAGRAPH = "Payment shall be made within thirty days after receipt of a valid invoice. "
            + "The customer may request supporting records before approving the amount due and shall notify "
            + "the supplier of any disputed charge.";
    static final String CHINESE_PARAGRAPH = "本合同由双方在平等自愿的基础上签订，双方应当按照约定履行各自义务。"
            + "服务提供方应当及时提交完整的工作记录，并在发生重大变更时通知对方，"
            + "双方共同确认后继续执行原定服务计划。";
    private static final String TITLE = "SERVICE AGREEMENT";
    private static final String FIRST_ITEM = "1. Review the delivery record.";
    private static final String SECOND_ITEM = "2. Confirm the invoice details.";
    private static final String NOTE = "A separately styled note remains independent.";
    private static final float PAGE_WIDTH = 500;
    private static final float LEFT = 50;
    private static final float RIGHT = 450;
    private static final float FIRST_LINE_INDENT = 24;
    private static final float SIZE = 12;
    private static final float LEADING = 18;
    @TempDir Path temp;

    @Test
    void reconstructsTwoEditableContractParagraphsWithSourceIndentsAndLineSpacing() throws Exception {
        Path source = writeEnglishContract(temp.resolve("contract.pdf"), false);
        PageModel sourcePage = parsed(source);
        double sourceRightPt = sourcePage.textBlocks().stream().mapToDouble(block -> block.box().right())
                .max().orElseThrow() * 72 / 25.4;
        try (XWPFDocument word = open(convert(source))) {
            List<XWPFParagraph> body = body(word);
            assertEquals(List.of(FIRST_PARAGRAPH, SECOND_PARAGRAPH), texts(body),
                    "源 PDF 的六个视觉行应成为两个完整段落，英文跨行处必须补一个词间空格");
            for (XWPFParagraph paragraph : body) {
                assertEquals(LEFT * 20, paragraph.getIndentationLeft(), 1);
                assertEquals(FIRST_LINE_INDENT * 20, paragraph.getIndentationFirstLine(), 1,
                        "首行缩进必须保留为段落属性，不能填入人工空格");
                assertEquals((PAGE_WIDTH - sourceRightPt) * 20, paragraph.getIndentationRight(), 2,
                        "右缩进应保留源正文边界，不能铺满页面");
                assertEquals(LEADING * 20,
                        Double.parseDouble(paragraph.getCTP().getPPr().getSpacing().getLine().toString()), 1);
                assertEquals("exact", paragraph.getCTP().getPPr().getSpacing().getLineRule().toString());
                assertFalse(paragraph.getText().startsWith(" "));
                assertNoHardLineBreaks(paragraph);
            }
            assertEditableBody(word);
        }
    }

    @Test
    void keepsCenteredTitleNumberedItemsAndDifferentFontSizeOutsideBodyParagraphs() throws Exception {
        Path source = writeEnglishContract(temp.resolve("structured-contract.pdf"), true);
        try (XWPFDocument word = open(convert(source))) {
            List<XWPFParagraph> body = body(word);
            assertEquals(List.of(TITLE, FIRST_PARAGRAPH, SECOND_PARAGRAPH, FIRST_ITEM, SECOND_ITEM, NOTE),
                    texts(body), "标题、列表和字号不同的备注都必须保留为独立段落");
            assertEquals(16, body.get(0).getRuns().get(0).getFontSizeAsDouble(), 0.01);
            assertEquals(10, body.get(5).getRuns().get(0).getFontSizeAsDouble(), 0.01);
            body.forEach(PdfParagraphReflowTest::assertNoHardLineBreaks);
            assertEditableBody(word);
        }
    }

    @Test
    void joinsThreeChineseLinesWithoutInjectingSpacesBetweenChineseCharacters() throws Exception {
        Path source = writeChineseReport(temp.resolve("chinese-report.pdf"));
        try (XWPFDocument word = open(convert(source))) {
            List<XWPFParagraph> body = body(word);
            assertEquals(List.of(CHINESE_PARAGRAPH), texts(body),
                    "中文正文跨行必须直接续接，不能添加英文词间空格或丢字");
            assertEquals(FIRST_LINE_INDENT * 20, body.get(0).getIndentationFirstLine(), 1);
            assertNoHardLineBreaks(body.get(0));
            assertEditableBody(word);
        }
    }

    @Test
    void editingAReconstructedParagraphAddsVisualLinesAndMovesFollowingParagraphInOffice() throws Exception {
        var binary = LibreOfficeConverter.discover("");
        assumeTrue(binary.isPresent(), "LibreOffice is not installed");
        Path source = writeEnglishContract(temp.resolve("editable-contract.pdf"), false);
        Path original = convert(source);
        Path before = renderInOffice(binary.orElseThrow(), original, "before-edit");
        String addition = " The parties may extend the delivery schedule by written agreement. "
                + "Each approved change must identify the revised scope, the responsible person, "
                + "and the date on which the updated service will be completed.";
        Path edited = temp.resolve("edited-contract.docx");
        try (XWPFDocument word = open(original)) {
            List<XWPFParagraph> paragraphs = body(word);
            assertEquals(2, paragraphs.size(), "实际编辑应针对完整段落，而不是源 PDF 的单行碎片");
            var run = paragraphs.get(0).createRun();
            run.setFontFamily("Arial");
            run.setFontSize(SIZE);
            run.setText(addition);
            try (var output = Files.newOutputStream(edited)) { word.write(output); }
        }
        Path after = renderInOffice(binary.orElseThrow(), edited, "after-edit");
        PageModel beforePage = parsed(before), afterPage = parsed(after);
        double beforeSecond = baselineOf(beforePage, "Payment"), afterSecond = baselineOf(afterPage, "Payment");
        assertTrue(afterSecond > beforeSecond + 2 * LEADING * 25.4 / 72 - 0.5,
                "新增正文应自动换行，并将后段至少下推两个视觉行，而不是覆盖或固定在原坐标");
        assertTrue(visualLineCountBefore(afterPage, afterSecond) >= visualLineCountBefore(beforePage, beforeSecond) + 2,
                "Office 排版后的首段必须实际增加视觉行");
        try (PDDocument beforePdf = Loader.loadPDF(before.toFile());
             PDDocument afterPdf = Loader.loadPDF(after.toFile())) {
            assertEquals(1, beforePdf.getNumberOfPages());
            assertEquals(1, afterPdf.getNumberOfPages(), "有足够空白的单页合同不应多出空白页");
            assertEquals(compact(FIRST_PARAGRAPH + SECOND_PARAGRAPH),
                    compact(new PDFTextStripper().getText(beforePdf)));
            assertEquals(compact(FIRST_PARAGRAPH + addition + SECOND_PARAGRAPH),
                    compact(new PDFTextStripper().getText(afterPdf)), "实际排版后新增和原有文字均须完整保留");
        }
    }

    /** Also used by manual HTTP/visual acceptance helpers to generate the same source PDF. */
    static Path writeEnglishContract(Path source, boolean decorations) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, 700));
            pdf.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                if (decorations) {
                    float titleX = (PAGE_WIDTH - font.getStringWidth(TITLE) * 16 / 1000) / 2;
                    text(content, font, 16, titleX, 665, TITLE);
                }
                writeWrappedParagraph(content, font, FIRST_PARAGRAPH, 620, false);
                writeWrappedParagraph(content, font, SECOND_PARAGRAPH, 548, false);
                if (decorations) {
                    text(content, font, SIZE, LEFT, 460, FIRST_ITEM);
                    text(content, font, SIZE, LEFT, 442, SECOND_ITEM);
                    text(content, font, 10, LEFT, 424, NOTE);
                }
            }
            Files.createDirectories(source.toAbsolutePath().getParent());
            pdf.save(source.toFile());
        }
        return source;
    }

    static Path writeChineseReport(Path source) throws Exception {
        try (PDDocument pdf = new PDDocument();
             var fontStream = PdfParagraphReflowTest.class.getResourceAsStream("/fonts/DroidSansFallback.ttf")) {
            assertNotNull(fontStream, "测试需要仓库自带中文字体");
            PDFont font = PDType0Font.load(pdf, fontStream);
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, 700));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                writeWrappedParagraph(content, font, CHINESE_PARAGRAPH, 620, true);
            }
            Files.createDirectories(source.toAbsolutePath().getParent());
            pdf.save(source.toFile());
        }
        return source;
    }

    private static void writeWrappedParagraph(PDPageContentStream content, PDFont font, String paragraph,
                                               float y, boolean chinese) throws Exception {
        List<String> tokens = chinese ? paragraph.codePoints().mapToObj(codePoint -> new String(Character.toChars(codePoint))).toList()
                : List.of(paragraph.split(" "));
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String token : tokens) {
            String candidate = line.isEmpty() ? token : line + (chinese ? "" : " ") + token;
            float available = RIGHT - LEFT - (lines.isEmpty() ? FIRST_LINE_INDENT : 0);
            if (!line.isEmpty() && font.getStringWidth(candidate) * SIZE / 1000 > available) {
                lines.add(line);
                line = token;
            } else line = candidate;
        }
        if (!line.isEmpty()) lines.add(line);
        assertEquals(3, lines.size(), "回归样本必须恰有三个视觉行");
        for (int index = 0; index < lines.size(); index++) {
            text(content, font, SIZE, LEFT + (index == 0 ? FIRST_LINE_INDENT : 0),
                    y - index * LEADING, lines.get(index));
        }
    }

    private static void text(PDPageContentStream content, PDFont font, float size, float x, float y, String value)
            throws Exception {
        content.beginText(); content.setFont(font, size); content.newLineAtOffset(x, y);
        content.showText(value); content.endText();
    }

    private Path convert(Path source) throws Exception {
        Path output = temp.resolve(source.getFileName().toString().replace(".pdf", ".docx"));
        new PdfToDocxConverter().convert(new ConversionInput(source.getFileName().toString(), "application/pdf",
                Files.size(source), source), temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });
        return output;
    }

    private Path renderInOffice(Path binary, Path source, String name) throws Exception {
        Path output = temp.resolve(name + ".pdf");
        new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF, binary, Duration.ofSeconds(45),
                "paragraph edit regression").convert(new ConversionInput(source.getFileName().toString(),
                        DocumentFormat.DOCX.contentType(), Files.size(source), source),
                temp.resolve(name + "-office-work"), output, ParseLimits.defaults(), (stage, percent) -> { });
        return output;
    }

    private static PageModel parsed(Path source) throws Exception {
        return new PdfLayoutParser().parse(source, source.getFileName().toString(), ParseLimits.defaults()).pages().get(0);
    }

    private static XWPFDocument open(Path path) throws Exception { return new XWPFDocument(Files.newInputStream(path)); }
    private static List<XWPFParagraph> body(XWPFDocument word) {
        return word.getParagraphs().stream().filter(paragraph -> !paragraph.getText().isBlank()).toList();
    }
    private static List<String> texts(List<XWPFParagraph> paragraphs) {
        return paragraphs.stream().map(XWPFParagraph::getText).toList();
    }
    private static void assertNoHardLineBreaks(XWPFParagraph paragraph) {
        assertTrue(paragraph.getRuns().stream().allMatch(run -> run.getCTR().sizeOfBrArray() == 0),
                "段落内不能用硬换行保留 PDF 原行，否则编辑后无法正常重排");
    }
    private static void assertEditableBody(XWPFDocument word) {
        assertTrue(word.getAllPictures().isEmpty(), "正文必须保留为真正可编辑文字");
        assertTrue(word.getTables().isEmpty());
        assertFalse(word.getDocument().xmlText().contains("txbxContent"), "普通单栏正文不能退化为逐行定位文本框");
    }
    private static double baselineOf(PageModel page, String marker) {
        return page.textBlocks().stream().filter(block -> block.text().contains(marker))
                .mapToDouble(block -> block.baselineY()).min().orElseThrow();
    }
    private static long visualLineCountBefore(PageModel page, double baseline) {
        return page.textBlocks().stream().filter(block -> block.baselineY() < baseline - 0.5)
                .mapToLong(block -> Math.round(block.baselineY() * 4)).distinct().count();
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
