package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PdfTaggedTextSectionsTest {
    @TempDir Path temp;
    @Test void respectsDeclaredSectionsBeforeOrderingEachWideColumnAndPreservesBlankPage() throws Exception {
        Path source = fixture("valid");
        String result = convert(source);
        String expected = "SECTION 1\nLEFT 1-1 ID00571\nLEFT 1-2 -742.63\nRIGHT 1-1 ID00924\nRIGHT 1-2 .47\n"
                + "SECTION 2\nLEFT 2-1 ID00571\nLEFT 2-2 -742.63\nRIGHT 2-1 ID00924\nRIGHT 2-2 .47\n\n\f\n";
        assertEquals(expected, result.replace("\r\n", "\n"));
        assertNotEquals(legacy(source), result);
    }
    @Test void repeatedContentMcidCannotAuthorizeSectionOrdering() throws Exception { unchanged("duplicate-content"); }
    @Test void nestedTagCannotEscapeArtifactFallback() throws Exception { unchanged("nested-artifact"); }
    @Test void overlappingDeclaredGroupsKeepExistingColumnOrder() throws Exception { unchanged("overlap"); }
    @Test void missingLastTagFallsBackWithoutDroppingAnyText() throws Exception { unchanged("partial"); }
    @Test void duplicateMcidAssignmentFallsBack() throws Exception { unchanged("duplicate"); }
    @Test void cyclicStructureFallsBackWithinBudget() throws Exception { unchanged("cycle"); }
    @Test void tableStructureNeverAcquiresColumnIntent() throws Exception { unchanged("table"); }
    @Test void untaggedPageRetainsExistingOrder() throws Exception { unchanged("untagged"); }
    @Test void unmarkedNativeTextPreventsPartialAdoption() throws Exception { unchanged("unmarked"); }
    @Test void streamScopedReferenceRetainsLegacyPath() throws Exception { unchanged("stream"); }
    @Test void unsupportedStructureRetainsLegacyPath() throws Exception { unchanged("unsupported"); }
    @Test void deeplyNestedMarkedContentFallsBack() throws Exception { unchanged("deep"); }
    @Test void unclosedMarkedContentFallsBack() throws Exception { unchanged("unclosed"); }
    @Test void formContentCannotAliasPageMcidNamespace() throws Exception { unchanged("form"); }
    @Test void outOfRangeMcidCannotWrapToAnotherId() throws Exception { unchanged("large-id"); }
    @Test void contentMcidMustNotOverflowIntoValidPageId() throws Exception { unchanged("large-content-id"); }
    @Test void fractionalContentMcidMustNotTruncateIntoValidPageId() throws Exception { unchanged("fractional-content-id"); }
    @Test void optionalStructureTraversalHasIndependentBudget() throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(fixture("valid").toFile())) {
            var order = new PdfTextSectionOrder();order.initialize(document, 1);assertFalse(order.enabled());
        }
    }
    private void unchanged(String mutation) throws Exception {
        Path source = fixture(mutation);assertEquals(legacy(source), convert(source));
    }
    private String convert(Path source) throws Exception {
        Path output=temp.resolve(source.getFileName()+".txt");
        var result=new PdfToTextConverter().convert(new ConversionInput(source.getFileName().toString(),"application/pdf",Files.size(source),source),
                temp.resolve("work-"+source.getFileName()),output,ParseLimits.defaults(),(s,p)->{});
        assertEquals(2,result.pageCount());return Files.readString(output);
    }
    private String legacy(Path source) throws Exception {
        var document=new PdfLayoutParser().parseForTextExtraction(source,"input.pdf",ParseLimits.defaults());
        return OfdToTextConverter.text(document.pages().stream().map(new PageLayoutAnalyzer()::analyze).toList());
    }
    private Path fixture(String mutation) throws Exception {
        Path source=temp.resolve(mutation+".pdf");
        try(var document=new PDDocument()) {
            var page=new PDPage(new PDRectangle(700,700));document.addPage(page);document.addPage(new PDPage(new PDRectangle(700,700)));
            var root=new COSDictionary();root.setItem(COSName.TYPE,COSName.STRUCT_TREE_ROOT);
            var owner=new COSDictionary();owner.setName(COSName.TYPE,"StructElem");owner.setName(COSName.S,"Document");owner.setItem(COSName.P,root);owner.setItem(COSName.PG,page);
            root.setItem(COSName.K,owner);var kids=new COSArray();owner.setItem(COSName.K,kids);
            int mcid=0;
            try(var stream=new PDPageContentStream(document,page)) {
                for(int section=1;section<=2;section++) {
                    var paragraph=new COSDictionary();paragraph.setName(COSName.TYPE,"StructElem");paragraph.setName(COSName.S,"P");paragraph.setItem(COSName.P,owner);paragraph.setItem(COSName.PG,page);kids.add(paragraph);
                    var content=new COSArray();paragraph.setItem(COSName.K,content);
                    float top=650-(section-1)*320;
                    String[] text={"SECTION "+section,"LEFT "+section+"-1 ID00571","RIGHT "+section+"-1 ID00924","LEFT "+section+"-2 -742.63","RIGHT "+section+"-2 .47"};
                    for(int line=0;line<text.length;line++) {
                        boolean marked=!(mutation.equals("unmarked")&&mcid==9);
                        if(mutation.equals("deep")&&mcid==0)for(int depth=0;depth<65;depth++)stream.beginMarkedContent(COSName.getPDFName("Span"));
                        if(marked) {
                            var props=new COSDictionary();props.setInt(COSName.MCID,mutation.equals("duplicate-content")&&mcid==9?8:mcid);
                            if(mutation.equals("nested-artifact")&&mcid==9)stream.beginMarkedContent(COSName.ARTIFACT);
                            // PDPageContentStream's property-list writer narrows MCID via getInt.
                            // Emit the malformed raw operand and verify saved bytes below.
                            if(mcid==0&&mutation.equals("large-content-id"))stream.appendRawCommands("/P <</MCID 4294967296>> BDC\n");
                            else if(mcid==0&&mutation.equals("fractional-content-id"))stream.appendRawCommands("/P <</MCID 0.5>> BDC\n");
                            else stream.beginMarkedContent(COSName.P,org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(props));
                        }
                        stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                        stream.newLineAtOffset(line>0&&line%2==0?430:42,top-(line==0?0:line<=2?55:95));stream.showText(text[line]);stream.endText();
                        if(marked&&!(mutation.equals("unclosed")&&mcid==0))stream.endMarkedContent();
                        if(mutation.equals("nested-artifact")&&mcid==9)stream.endMarkedContent();
                        if(mutation.equals("deep")&&mcid==0)for(int depth=0;depth<65;depth++)stream.endMarkedContent();
                        if(!(mutation.equals("partial")&&mcid==9))content.add(COSInteger.get(mutation.equals("large-id")&&mcid==0?1L<<32:mutation.equals("duplicate")&&mcid==9?0:mcid));
                        mcid++;
                    }
                    if(section==2) {
                        if(mutation.equals("cycle"))content.add(paragraph);
                        if(mutation.equals("table"))paragraph.setName(COSName.S,"Table");
                        if(mutation.equals("unsupported"))paragraph.setName(COSName.S,"Figure");
                        if(mutation.equals("stream"))paragraph.setItem(COSName.getPDFName("Stm"),new COSDictionary());
                    }
                }
            }
            if(mutation.equals("overlap")) {
                var first=(COSArray)((COSDictionary)kids.getObject(0)).getDictionaryObject(COSName.K);
                var second=(COSArray)((COSDictionary)kids.getObject(1)).getDictionaryObject(COSName.K);
                first.add(second.getObject(4));second.remove(4);
            }
            if(mutation.equals("form")) {
                var form=new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(document);
                form.setResources(new PDResources());form.setBBox(new PDRectangle(700,700));
                try(var stream=new org.apache.pdfbox.pdmodel.PDFormContentStream(form)) {
                    var props=new COSDictionary();props.setInt(COSName.MCID,0);
                    stream.beginMarkedContent(COSName.P,org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(props));
                    stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(42,60);stream.showText("FORM EXTRA ID00168");stream.endText();stream.endMarkedContent();
                }
                try(var stream=new PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true)) { stream.drawForm(form); }
            }
            if(!mutation.equals("untagged"))document.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT,root);
            document.save(source.toFile());
        }
        if(mutation.equals("large-content-id")||mutation.equals("fractional-content-id")) {
            try(var saved=org.apache.pdfbox.Loader.loadPDF(source.toFile());var bytes=saved.getPage(0).getContents()) {
                String content=new String(bytes.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(content.contains("/MCID "+(mutation.equals("large-content-id")?"4294967296":"0.5")),"invalid numeric operand must survive producer");
            }
        }
        return source;
    }
}
