package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class PdfCidCffMetadataTest {
    @Test void readsActualEmbeddedCidCffFamily() throws Exception {
        verify("regular", null, "QA Embedded CJK", false, false);
    }
    @Test void retainsCffWeightAndItalicAngleWithoutPdfStyleHints() throws Exception {
        verify("boldItalic", null, "QA Embedded CJK", true, true);
    }
    @Test void explicitDescriptorFamilyHasPriority() throws Exception {
        verify("boldItalic", "QA Missing Family", "QA Missing Family", true, true);
    }
    @Test void absentCffFamilyRetainsPdfName() throws Exception {
        verify("missingFamily", null, "QACjkExportFace", false, false);
    }
    @Test void installedSubstituteIsNeverReadAsEmbeddedMetadata() throws Exception {
        verify(null, null, "QACjkExportFace", false, false);
    }

    private void verify(String fixture, String family, String expected, boolean bold, boolean italic) throws Exception {
        try (var document = new PDDocument()) {
            var descriptor = new PDFontDescriptor(new COSDictionary());
            descriptor.setFontName("ABCDEF+QACjkExportFace");
            descriptor.setFontBoundingBox(new PDRectangle(0, 0, 600, 700));
            descriptor.setFlags(4);
            if (family != null) descriptor.setFontFamily(family);
            if (fixture != null) {
                try (var input = getClass().getResourceAsStream("/fonts/qa-cid-cff.json")) {
                    assertNotNull(input);
                    byte[] bytes = Base64.getDecoder().decode(new ObjectMapper().readTree(input).get(fixture).asText());
                    var stream = new PDStream(document, new ByteArrayInputStream(bytes));
                    stream.getCOSObject().setName(COSName.SUBTYPE, "CIDFontType0C");
                    descriptor.setFontFile3(stream);
                }
            }
            var system = new COSDictionary();
            system.setString(COSName.REGISTRY, "Adobe");system.setString(COSName.ORDERING, "Identity");
            system.setInt(COSName.SUPPLEMENT, 0);
            var descendant = new COSDictionary();
            descendant.setItem(COSName.TYPE, COSName.FONT);
            descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
            descendant.setName(COSName.BASE_FONT, "ABCDEF+QACjkExportFace");
            descendant.setItem(COSName.CIDSYSTEMINFO, system);
            descendant.setItem(COSName.FONT_DESC, descriptor);
            var descendants = new COSArray();descendants.add(descendant);
            var dictionary = new COSDictionary();
            dictionary.setItem(COSName.TYPE, COSName.FONT);dictionary.setItem(COSName.SUBTYPE, COSName.TYPE0);
            dictionary.setName(COSName.BASE_FONT, "ABCDEF+QACjkExportFace");
            dictionary.setName(COSName.ENCODING, "Identity-H");dictionary.setItem(COSName.DESCENDANT_FONTS, descendants);
            var font = new PDType0Font(dictionary);
            assertEquals(fixture != null, font.isEmbedded());
            if (fixture != null) assertNotNull(((PDCIDFontType0) font.getDescendantFont()).getCFFFont());
            assertEquals(new PdfFontNames.Face(expected, bold, italic), PdfFontNames.from(font));
        }
    }
}
