package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfToDocxFontTest {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({
            "HELVETICA, Arial, false, false",
            "HELVETICA_BOLD_OBLIQUE, Arial, true, true",
            "TIMES_ROMAN, Times New Roman, false, false",
            "TIMES_BOLD_ITALIC, Times New Roman, true, true",
            "COURIER, Courier New, false, false",
            "COURIER_BOLD_OBLIQUE, Courier New, true, true"
    })
    void usesWordFamilyNamesForStandardPdfFaces(Standard14Fonts.FontName name, String family,
                                                boolean bold, boolean italic) throws Exception {
        check(new PDType1Font(name), family, bold, italic);
    }

    @ParameterizedTest
    @CsvSource({
            "ABCDEF+ArialMT, Arial, Arial, false, false",
            "ABCDEF+ArialMT, , Arial, false, false",
            "TimesNewRomanPSMT, , Times New Roman, false, false",
            "CourierNewPSMT, , Courier New, false, false",
            "ABCDEF+Arial-BoldMT, Arial, Arial, true, false",
            "Arial-BoldItalicMT, Arial, Arial, true, true",
            "TimesNewRomanPS-ItalicMT, Times New Roman, Times New Roman, false, true",
            "CourierNewPS-BoldItalicMT, Courier New, Courier New, true, true",
            "ABCDEF+AcmeSans-BoldItalic, Acme Sans, Acme Sans, true, true"
    })
    void preservesStylesFromPostscriptNameWhenDescriptorOnlyNamesTheFamily(String postscript,
            String descriptorFamily, String wordFamily, boolean bold, boolean italic) throws Exception {
        check(namedFont(postscript, descriptorFamily, 400, 0), wordFamily, bold, italic);
    }

    @ParameterizedTest
    @CsvSource({
            "NovelBlackbird-Regular, Novel Blackbird, Novel Blackbird",
            "ABCDEF+CustomTypeface-Regular, , CustomTypeface-Regular",
            "ArialNarrow-Regular, Arial Narrow, Arial Narrow"
    })
    void preservesUnknownFontFamiliesWithoutGuessingFromNameSubstrings(String postscript,
            String descriptorFamily, String wordFamily) throws Exception {
        check(namedFont(postscript, descriptorFamily, 400, 0), wordFamily, false, false);
    }

    @Test
    void alsoHonorsDescriptorStyleWhenTheNameIsUnstyled() throws Exception {
        check(namedFont("AcmeSans", "Acme Sans", 700, -12), "Acme Sans", true, true);
    }

    private void check(PDFont font, String family, boolean bold, boolean italic) throws Exception {
        Path source = temp.resolve("font.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(500, 300));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(font, 14);
                content.newLineAtOffset(50, 240); content.showText("Editable typeface"); content.endText();
            }
            pdf.save(source.toFile());
        }
        FontStyle style = new PdfLayoutParser().parse(source, "font.pdf", ParseLimits.defaults())
                .pages().get(0).textBlocks().get(0).style();
        assertEquals(family, style.family(), "PDF 字体名称应还原为 Word 可识别的字体族");
        assertEquals(bold, style.bold(), "基础字体族名称不得掩盖 PDF 的粗体样式");
        assertEquals(italic, style.italic(), "基础字体族名称不得掩盖 PDF 的斜体样式");

        Path output = temp.resolve("font.docx");
        new PdfToDocxConverter().convert(new ConversionInput("font.pdf", "application/pdf",
                        Files.size(source), source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> {});
        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            XWPFRun run = word.getParagraphs().stream().flatMap(paragraph -> paragraph.getRuns().stream())
                    .filter(candidate -> candidate.text().contains("Editable typeface")).findFirst().orElseThrow();
            assertEquals(family, run.getFontFamily());
            assertEquals(bold, run.isBold());
            assertEquals(italic, run.isItalic());
            assertEquals(14, run.getFontSizeAsDouble(), 0.01);
            assertTrue(word.getAllPictures().isEmpty(), "字体恢复后仍然必须保留可编辑文字");
        }
    }

    /** A real PDF font whose exporter supplies a family but omits style flags in its descriptor. */
    private PDFont namedFont(String postscript, String family, float weight, float angle) throws Exception {
        COSDictionary dictionary = new COSDictionary();
        dictionary.setItem(COSName.TYPE, COSName.FONT);
        dictionary.setItem(COSName.SUBTYPE, COSName.TYPE1);
        dictionary.setName(COSName.BASE_FONT, postscript);
        dictionary.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
        dictionary.setInt(COSName.FIRST_CHAR, 32);
        dictionary.setInt(COSName.LAST_CHAR, 126);
        COSArray widths = new COSArray();
        PDFont metrics = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        for (int character = 32; character <= 126; character++) {
            widths.add(COSInteger.get(Math.round(metrics.getWidth(character))));
        }
        dictionary.setItem(COSName.WIDTHS, widths);
        PDFontDescriptor descriptor = new PDFontDescriptor(new COSDictionary());
        descriptor.setFontName(postscript);
        if (family != null) descriptor.setFontFamily(family);
        descriptor.setFontBoundingBox(new PDRectangle(-170, -225, 1186, 1156));
        descriptor.setNonSymbolic(true);
        descriptor.setAscent(718);
        descriptor.setDescent(-207);
        descriptor.setCapHeight(718);
        descriptor.setStemV(80);
        descriptor.setFontWeight(weight);
        descriptor.setItalicAngle(angle);
        dictionary.setItem(COSName.FONT_DESC, descriptor);
        return new PDType1Font(dictionary);
    }
}
