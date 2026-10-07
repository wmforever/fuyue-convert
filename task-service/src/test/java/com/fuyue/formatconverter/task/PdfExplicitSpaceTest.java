package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real encoded-space overlap and numeric-boundary regressions, without OCR. */
class PdfExplicitSpaceTest {
    @TempDir Path temp;

    @Test void restoresExplicitLatinYearBoundaryDroppedByVisualSorting() throws Exception {
        Path source=fixture("AUDIT","2071",1,0,false,false);
        assertEquals("AUDIT2071",pdfBox(source).strip(),"fixture must exercise upstream loss");
        assertEquals("AUDIT 2071\n",convert(source));
    }
    @Test void preservesSeparateNumbersAndDecimalPunctuation() throws Exception {
        assertEquals("12 34\n",convert(fixture("12","34",1,0,false,false)));
        assertEquals("12.30\n",convert(fixture("12.","30",0,0,false,false)));
    }
    @Test void restoresBothEncodedSpacesWithoutMovingOneInsideYear() throws Exception {
        assertEquals("AUDIT  2074\n",convert(fixture("AUDIT","2074",2,0,false,false)));
    }
    @Test void neverInventsSpaceForContiguousIdsEvenAcrossColorsOrLargeVisualGaps() throws Exception {
        assertEquals("CODE00793\n",convert(fixture("CODE","00793",0,0,false,false)));
        assertEquals("ID00864\n",convert(fixture("ID","00864",0,0,true,false)));
        assertEquals("CODE00796\n",convert(fixture("CODE","00796",0,0,false,true)));
    }
    @Test void ordinaryExplicitSpacesAndNumericSurfacesRemainExact() throws Exception {
        assertEquals("Amount -054.80 +0.47\n",convert(fixture("Amount -054.80 +0.47","",0,0,false,false)));
        assertEquals("Date 2071-11-23 ID00793\n",convert(fixture("Date 2071-11-23 ID00793","",0,0,false,false)));
    }
    @Test void txtRecoveryLeavesEveryWordGeometryAndStyleUnchanged() throws Exception {
        Path source=fixture("AUDIT","2071",1,0,true,false);
        var parser=new PdfLayoutParser();
        var legacy=parser.parseForFixedLayout(source,"input.pdf",ParseLimits.defaults());
        var fixedAgain=parser.parseForEditableOcr(source,"input.pdf",ParseLimits.defaults());
        assertEquals(legacy,fixedAgain);
        var txt=parser.parseForTextExtraction(source,"input.pdf",ParseLimits.defaults());
        var before=legacy.pages().get(0).textBlocks();var after=txt.pages().get(0).textBlocks();
        assertEquals(before.size(),after.size());
        for(int i=0;i<before.size();i++) {
            assertEquals(before.get(i).box(),after.get(i).box());
            assertEquals(before.get(i).style(),after.get(i).style());
            assertEquals(before.get(i).transform(),after.get(i).transform());
            assertEquals(before.get(i).id(),after.get(i).id());
            assertEquals(before.get(i).baselineY(),after.get(i).baselineY());
            assertEquals(before.get(i).textOffsetXmm(),after.get(i).textOffsetXmm());
            assertEquals(before.get(i).textOffsetYmm(),after.get(i).textOffsetYmm());
            assertEquals(before.get(i).advancesMm(),after.get(i).advancesMm());
            assertEquals(before.get(i).ocrWords(),after.get(i).ocrWords());
            assertEquals(before.get(i).text().replace(" ",""),after.get(i).text().replace(" ",""));
        }
        assertEquals("AUDIT 2071\n",convert(source));
    }
    @Test void rotatedPagesRetainExistingFallback() throws Exception {
        Path source=fixture("AUDIT","2071",1,90,false,false);
        var parser=new PdfLayoutParser();
        assertEquals(parser.parseForFixedLayout(source,"input.pdf",ParseLimits.defaults()),
                parser.parseForTextExtraction(source,"input.pdf",ParseLimits.defaults()));
    }
    @Test void nonUnitPagesRetainExistingFallback() throws Exception {
        Path source=fixture("AUDIT","2071",1,0,false,false);
        try(var doc=Loader.loadPDF(source.toFile())) { doc.getPage(0).setUserUnit(2);doc.save(temp.resolve("scaled.pdf").toFile()); }
        source=temp.resolve("scaled.pdf");var parser=new PdfLayoutParser();
        assertEquals(parser.parseForFixedLayout(source,"input.pdf",ParseLimits.defaults()),
                parser.parseForTextExtraction(source,"input.pdf",ParseLimits.defaults()));
    }
    @Test void formNamespaceRetainsExistingFallback() throws Exception {
        Path source=fixture("AUDIT","2071",1,0,false,false);
        try(var doc=Loader.loadPDF(source.toFile())) {
            var form=new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(doc);
            form.setResources(new PDResources());form.setBBox(PDRectangle.A4);
            try(var stream=new PDFormContentStream(form)) { stream.addRect(10,10,1,1);stream.fill(); }
            try(var stream=new PDPageContentStream(doc,doc.getPage(0),PDPageContentStream.AppendMode.APPEND,true)) { stream.drawForm(form); }
            doc.save(temp.resolve("form.pdf").toFile());
        }
        source=temp.resolve("form.pdf");var parser=new PdfLayoutParser();
        assertEquals(parser.parseForFixedLayout(source,"input.pdf",ParseLimits.defaults()),
                parser.parseForTextExtraction(source,"input.pdf",ParseLimits.defaults()));
    }
    @Test void optionalRecoveryFallsBackWhenItsEntryOrLineBudgetIsExceeded() throws Exception {
        var raw=new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
        Path source=fixture("A B C","",0,0,false,false);
        try(var doc=Loader.loadPDF(source.toFile())) {
            new PDFTextStripper() {
                @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition p) { raw.add(p);super.processTextPosition(p); }
            }.getText(doc);
        }
        var bounded=new PdfExplicitSpaces(1);bounded.reset(false);raw.forEach(bounded::record);
        assertSame(PdfExplicitSpaces.Text.EMPTY,bounded.restore(raw));
        var normal=new PdfExplicitSpaces(8);normal.reset(false);raw.forEach(normal::record);
        assertSame(PdfExplicitSpaces.Text.EMPTY,normal.restore(java.util.Collections.nCopies(100_001,raw.get(0))));
    }
    private Path fixture(String first,String second,int spaces,int rotation,boolean color,boolean wide) throws Exception {
        Path source=Files.createTempFile(temp,"boundary-",".pdf");
        try(var doc=new PDDocument();var fontBytes=getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            var font=PDType0Font.load(doc,fontBytes);var page=new PDPage(PDRectangle.A4);page.setRotation(rotation);doc.addPage(page);
            try(var c=new PDPageContentStream(doc,page)) {
                if(color)c.setNonStrokingColor(java.awt.Color.RED);
                text(c,font,40,720,first+" ".repeat(spaces));
                if(color)c.setNonStrokingColor(java.awt.Color.BLACK);
                if(!second.isEmpty())text(c,font,wide?140:40+font.getStringWidth(first)/1000*18-1.5f,720,second);
            }
            doc.save(source.toFile());
        }
        return source;
    }
    private void text(PDPageContentStream c,PDFont font,float x,float y,String text) throws Exception {
        c.beginText();c.setFont(font,18);c.newLineAtOffset(x,y);c.showText(text);c.endText();
    }
    private String pdfBox(Path source) throws Exception {
        try(var doc=Loader.loadPDF(source.toFile())) { var stripper=new PDFTextStripper();stripper.setSortByPosition(true);return stripper.getText(doc); }
    }
    private String convert(Path source) throws Exception {
        Path output=Files.createTempFile(temp,"boundary-",".txt");
        new PdfToTextConverter().convert(new ConversionInput("input.pdf","application/pdf",Files.size(source),source),
                Files.createTempDirectory(temp,"work-"),output,ParseLimits.defaults(),(s,p)->{});
        return Files.readString(output).replace("\r\n","\n");
    }
}
