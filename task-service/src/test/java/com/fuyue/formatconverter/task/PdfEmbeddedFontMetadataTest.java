package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PdfEmbeddedFontMetadataTest {
    @TempDir Path temp;
    @Test void readsCompositeEmbeddedFamilyInsteadOfFullPdfFaceName() throws Exception { verify(false,null,"Liberation Sans"); }
    @Test void readsSimpleTrueTypeEmbeddedFamily() throws Exception { verify(true,null,"Liberation Sans"); }
    @Test void explicitPdfFamilyStillHasPriorityOverEmbeddedMetadata() throws Exception { verify(false,"QA Missing Family 2032","QA Missing Family 2032"); }

    private void verify(boolean simple,String explicitFamily,String expected) throws Exception {
        Path source=temp.resolve("font.pdf"),word=temp.resolve("font.docx");
        String text="Editable font: ID 00731; -528.64 / 2032-09-24; .28.";
        try(var pdf=new PDDocument();var input=getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            assertNotNull(input);
            PDFont font=simple?PDTrueTypeFont.load(pdf,input,WinAnsiEncoding.INSTANCE):PDType0Font.load(pdf,input,false);
            font.getCOSObject().setName(COSName.BASE_FONT,"QAExportedFullFace-Regular");
            font.getFontDescriptor().setFontName("QAExportedFullFace-Regular");
            if(explicitFamily!=null)font.getFontDescriptor().setFontFamily(explicitFamily);
            else font.getFontDescriptor().getCOSObject().removeItem(COSName.FONT_FAMILY);
            var page=new PDPage(new PDRectangle(600,300));pdf.addPage(page);
            try(var stream=new PDPageContentStream(pdf,page)) {
                stream.beginText();stream.setFont(font,12);stream.newLineAtOffset(42,230);stream.showText(text);stream.endText();
            }
            pdf.save(source.toFile());
        }
        var parsed=new PdfLayoutParser().parse(source,"font.pdf",ParseLimits.defaults());
        var block=parsed.pages().get(0).textBlocks().get(0);
        assertEquals(text,block.text());assertEquals(expected,block.style().family());
        assertFalse(block.style().bold());assertFalse(block.style().italic());
        assertEquals(42*25.4/72,block.box().x(),.001);assertEquals(12,block.style().sizePt(),.001);
        new PdfToDocxConverter().convert(new ConversionInput("font.pdf","application/pdf",Files.size(source),source),
                temp.resolve("work"),word,ParseLimits.defaults(),(s,p)->{});
        try(var document=new XWPFDocument(Files.newInputStream(word))) {
            var runs=document.getParagraphs().stream().flatMap(p->p.getRuns().stream()).toList();
            assertEquals(text,runs.stream().map(r->r.text()).reduce("",String::concat));
            assertTrue(runs.stream().filter(r->!r.text().isEmpty()).allMatch(r->expected.equals(r.getFontFamily())));
            assertTrue(document.getAllPictures().isEmpty());
        }
    }
}
