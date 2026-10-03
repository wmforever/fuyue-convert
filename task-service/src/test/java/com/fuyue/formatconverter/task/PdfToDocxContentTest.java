package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfToDocxContentTest {
    @TempDir Path temp;
    private final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @Test void tableOnlyPageContainsEveryCellOnceWithoutDuplicatingItAsBodyText() throws Exception {
        Path source = tablePdf(false);
        try (XWPFDocument word = convert(source)) {
            assertEquals(1, word.getTables().size());
            assertEquals(List.of("ALPHA1", "BETA2", "GAMMA3", "DELTA4"),
                    word.getTables().get(0).getRows().stream()
                            .flatMap(row -> row.getTableCells().stream()).map(cell -> cell.getText()).toList());
            assertTrue(word.getParagraphs().stream().allMatch(paragraph -> paragraph.getText().isBlank()),
                    "文字已进入表格后不得再输出一次正文");
            String xml = word.getDocument().xmlText();
            for (String value : List.of("ALPHA1", "BETA2", "GAMMA3", "DELTA4")) {
                assertEquals(1, xml.split(value, -1).length - 1, "内容必须守恒：" + value);
            }
            assertTrue(word.getAllPictures().isEmpty(), "有真实文字的规则表格不得变成页面图片");
        }
    }

    @Test void restoresWordSeparatorsInCellsWhenPdfUsesPositionedWordsInsteadOfSpaceGlyphs() throws Exception {
        Path source = tablePdf(true);
        try (XWPFDocument word = convert(source)) {
            assertEquals(1, word.getTables().size());
            String cell = word.getTables().get(0).getRow(0).getCell(0).getText();
            assertEquals("Invoice Total", cell.replaceAll("\\s+", " ").strip());
        }
    }

    private Path tablePdf(boolean positionedWords) throws Exception {
        Path source = temp.resolve(positionedWords ? "cell-words.pdf" : "table-only.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(500, 500));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                for (float x : new float[]{50, 250, 450}) {
                    content.moveTo(x, 250); content.lineTo(x, 350);
                }
                for (float y : new float[]{250, 300, 350}) {
                    content.moveTo(50, y); content.lineTo(450, y);
                }
                content.stroke();
                if (positionedWords) {
                    text(content, 65, 322, "Invoice");
                    text(content, 65 + font.getStringWidth("Invoice") * 12 / 1000 + 4, 322, "Total");
                } else text(content, 65, 322, "ALPHA1");
                text(content, 265, 322, "BETA2");
                text(content, 65, 272, "GAMMA3");
                text(content, 265, 272, "DELTA4");
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private void text(PDPageContentStream content, float x, float y, String value) throws Exception {
        content.beginText(); content.setFont(font, 12); content.newLineAtOffset(x, y);
        content.showText(value); content.endText();
    }

    private XWPFDocument convert(Path source) throws Exception {
        Path output = temp.resolve(source.getFileName() + ".docx");
        new PdfToDocxConverter().convert(new ConversionInput(source.getFileName().toString(),
                        "application/pdf", Files.size(source), source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> {});
        return new XWPFDocument(Files.newInputStream(output));
    }
}
