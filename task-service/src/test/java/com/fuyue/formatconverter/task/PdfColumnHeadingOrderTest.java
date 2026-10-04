package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PdfColumnHeadingOrderTest {
    @TempDir Path temp;
    @Test void headingsSeparateTwoColumnRegionsInDocumentTextOrder() throws Exception { verify(1); }
    @Test void columnAnchorsStayWithTheirOwnPageAndHeading() throws Exception { verify(2); }
    private void verify(int pages) throws Exception {
        Path source=temp.resolve("headings.pdf"),output=temp.resolve("headings.docx");
        List<String> expected=new ArrayList<>();
        try(var pdf=new PDDocument()) {
            var font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for(int p=1;p<=pages;p++) {
                var page=new PDPage(PDRectangle.A4);pdf.addPage(page);
                try(var stream=new PDPageContentStream(pdf,page)) {
                    for(int section=1;section<=2;section++) {
                        float top=800-(section-1)*200;
                        String heading="PAGE "+p+" SECTION "+section+" HEADING ACROSS THE DOCUMENT";
                        draw(stream,font,50,top,heading);expected.add(heading);
                        List<String> left=new ArrayList<>(),right=new ArrayList<>();
                        for(int row=1;row<=2;row++) {
                            String l="L"+p+section+row+" ID00643 .95",r="R"+p+section+row+" ID00817 -17.40";
                            draw(stream,font,50,top-row*30,l);draw(stream,font,350,top-row*30,r);left.add(l);right.add(r);
                        }
                        expected.addAll(left);expected.addAll(right);
                    }
                }
            }
            pdf.save(source.toFile());
        }
        new PdfToDocxConverter().convert(new ConversionInput("headings.pdf","application/pdf",Files.size(source),source),
                temp.resolve("work"),output,ParseLimits.defaults(),(s,p)->{});
        try(var archive=new ZipFile(output.toFile())) {
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            var document=factory.newDocumentBuilder().parse(archive.getInputStream(archive.getEntry("word/document.xml")));
            var nodes=document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","t");
            List<String> actual=new ArrayList<>();for(int i=0;i<nodes.getLength();i++)actual.add(nodes.item(i).getTextContent());
            assertEquals(expected,actual,"ordinary headings and each column region must interleave in source reading order");
            assertEquals(pages*8,document.getElementsByTagNameNS("urn:schemas-microsoft-com:vml","shape").getLength());
            assertEquals(pages-1,document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","pageBreakBefore").getLength());
        }
    }
    private void draw(PDPageContentStream stream,PDFont font,float x,float y,String text) throws Exception {
        stream.beginText();stream.setFont(font,11);stream.newLineAtOffset(x,y);stream.showText(text);stream.endText();
    }
}
