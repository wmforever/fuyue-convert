package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextOutputConverterTest {
    @TempDir Path temp;

    @Test
    void createsEditableDocxWithCjkFontsAndExplicitPageBreak() throws Exception {
        Path source = writeUtf16Text("第一页中文\n第二行\f第二页中文");
        Path output = temp.resolve("result.docx");

        new TextToDocxConverter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(output))) {
            String allText = document.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(allText.contains("第一页中文"));
            assertTrue(allText.contains("第二行"));
            assertTrue(allText.contains("第二页中文"));
            long pageBreaks = document.getParagraphs().stream()
                    .filter(paragraph -> paragraph.getCTP().xmlText().contains("type=\"page\""))
                    .count();
            assertEquals(1, pageBreaks);
            XWPFRun cjkRun = document.getParagraphs().get(0).getRuns().get(0);
            assertNotNull(cjkRun.getFontFamily(XWPFRun.FontCharRange.ascii));
            assertNotNull(cjkRun.getFontFamily(XWPFRun.FontCharRange.eastAsia));
        }
    }

    @Test
    void createsTwoPagePdfWithSelectableCjkText() throws Exception {
        Path source = writeUtf16Text("第一页中文\n第一段\f第二页中文");
        Path output = temp.resolve("result.pdf");

        ConversionOutput result = new TextToPdfConverter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals(2, result.pageCount());
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(2, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("第一页中文"));
            assertTrue(text.contains("第二页中文"));
        }
    }

    @Test
    void enforcesPageLimitAfterAutomaticPdfPagination() throws Exception {
        Path source = temp.resolve("long.txt");
        Files.writeString(source, ("自动分页中文行\n").repeat(100), StandardCharsets.UTF_8);
        Path output = temp.resolve("long.pdf");
        ParseLimits onePage = new ParseLimits(1024 * 1024, 10 * 1024 * 1024,
                10 * 1024 * 1024, 100, 100, 1);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new TextToPdfConverter().convert(input(source), temp.resolve("work"), output,
                        onePage, (stage, percent) -> { }));

        assertEquals("PAGE_LIMIT_EXCEEDED", failure.code());
    }

    private Path writeUtf16Text(String value) throws Exception {
        Path source = temp.resolve("source.txt");
        byte[] content = value.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[content.length + 2];
        withBom[0] = (byte) 0xff;
        withBom[1] = (byte) 0xfe;
        System.arraycopy(content, 0, withBom, 2, content.length);
        Files.write(source, withBom);
        return source;
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "text/plain", Files.size(source), source);
    }
}
