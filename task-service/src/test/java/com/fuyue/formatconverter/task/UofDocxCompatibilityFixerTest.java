package com.fuyue.formatconverter.task;

import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UofDocxCompatibilityFixerTest {
    @TempDir Path temp;

    @Test
    void reconnectsOldListIdThatLibreOfficePlacesAfterANewEquivalentRestart() throws Exception {
        Path docx = temp.resolve("continued-list.docx");
        BigInteger oldList;
        BigInteger restartedList;
        try (XWPFDocument document = new XWPFDocument()) {
            var numbering = document.createNumbering();
            BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(
                    numbering(BigInteger.ZERO, STNumberFormat.LOWER_ROMAN)));
            oldList = numbering.addNum(abstractId);
            restartedList = numbering.addNum(abstractId);
            var override = numbering.getNum(restartedList).getCTNum().addNewLvlOverride();
            override.setIlvl(BigInteger.ZERO);
            override.addNewStartOverride().setVal(BigInteger.ONE);

            numbered(document, "One", oldList);
            numbered(document, "Two", oldList);
            document.createParagraph().createRun().setText("interruption");
            numbered(document, "Resume one", restartedList);
            numbered(document, "Resume two", oldList);
            try (var output = Files.newOutputStream(docx)) { document.write(output); }
        }

        assertEquals(1, UofDocxCompatibilityFixer.repairContinuedLists(docx));

        try (XWPFDocument repaired = new XWPFDocument(Files.newInputStream(docx))) {
            assertEquals(restartedList, repaired.getParagraphs().get(3).getNumID());
            assertEquals(restartedList, repaired.getParagraphs().get(4).getNumID());
        }
        assertEquals(0, UofDocxCompatibilityFixer.repairContinuedLists(docx));
    }

    @Test
    void leavesDifferentNumberingDefinitionsUntouched() throws Exception {
        Path docx = temp.resolve("different-lists.docx");
        BigInteger oldList;
        try (XWPFDocument document = new XWPFDocument()) {
            var numbering = document.createNumbering();
            BigInteger roman = numbering.addAbstractNum(new XWPFAbstractNum(
                    numbering(BigInteger.ZERO, STNumberFormat.LOWER_ROMAN)));
            BigInteger decimal = numbering.addAbstractNum(new XWPFAbstractNum(
                    numbering(BigInteger.ONE, STNumberFormat.DECIMAL)));
            oldList = numbering.addNum(roman);
            BigInteger restartedList = numbering.addNum(decimal);
            var override = numbering.getNum(restartedList).getCTNum().addNewLvlOverride();
            override.setIlvl(BigInteger.ZERO);
            override.addNewStartOverride().setVal(BigInteger.ONE);

            numbered(document, "Old", oldList);
            document.createParagraph().createRun().setText("interruption");
            numbered(document, "Decimal", restartedList);
            numbered(document, "Roman", oldList);
            try (var output = Files.newOutputStream(docx)) { document.write(output); }
        }

        assertEquals(0, UofDocxCompatibilityFixer.repairContinuedLists(docx));
        try (XWPFDocument unchanged = new XWPFDocument(Files.newInputStream(docx))) {
            assertEquals(oldList, unchanged.getParagraphs().get(3).getNumID());
        }
    }

    @Test
    void placesActualEndnotesOnANewPageAndRemainsIdempotent() throws Exception {
        Path docx = temp.resolve("endnotes.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("last body paragraph");
            document.createParagraph();
            try (var output = Files.newOutputStream(docx)) { document.write(output); }
        }
        addEndnotes(docx, """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:endnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:endnote w:id="0" w:type="separator"><w:p/></w:endnote>
                  <w:endnote w:id="2"><w:p><w:r><w:t>editable endnote</w:t></w:r></w:p></w:endnote>
                </w:endnotes>
                """);

        var repaired = UofDocxCompatibilityFixer.repair(docx);

        assertEquals(0, repaired.continuedLists());
        assertTrue(repaired.endnotePageBreak());
        String documentXml = zipEntry(docx, "word/document.xml");
        assertTrue(documentXml.contains("w:type=\"page\""), documentXml);
        assertTrue(documentXml.indexOf("last body paragraph") < documentXml.indexOf("w:type=\"page\""));
        assertFalse(UofDocxCompatibilityFixer.repair(docx).changed());
    }

    @Test
    void doesNotAddPageBreakForSeparatorMetadataWithoutActualEndnotes() throws Exception {
        Path docx = temp.resolve("endnote-separator-only.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("body");
            try (var output = Files.newOutputStream(docx)) { document.write(output); }
        }
        addEndnotes(docx, """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:endnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:endnote w:id="0" w:type="separator"><w:p/></w:endnote>
                  <w:endnote w:id="1" w:type="continuationSeparator"><w:p/></w:endnote>
                </w:endnotes>
                """);

        assertFalse(UofDocxCompatibilityFixer.repair(docx).changed());
        assertFalse(zipEntry(docx, "word/document.xml").contains("w:type=\"page\""));
    }

    private CTAbstractNum numbering(BigInteger abstractId, STNumberFormat.Enum format) {
        CTAbstractNum result = CTAbstractNum.Factory.newInstance();
        result.setAbstractNumId(abstractId);
        var level = result.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewStart().setVal(BigInteger.ONE);
        level.addNewNumFmt().setVal(format);
        level.addNewLvlText().setVal("%1.");
        return result;
    }

    private void numbered(XWPFDocument document, String text, BigInteger numId) {
        var paragraph = document.createParagraph();
        paragraph.setNumID(numId);
        paragraph.setNumILvl(BigInteger.ZERO);
        paragraph.createRun().setText(text);
    }

    private void addEndnotes(Path docx, String endnotesXml) throws Exception {
        Path updated = temp.resolve(docx.getFileName().toString() + ".updated");
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(docx));
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(updated))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                input.transferTo(output);
                output.closeEntry();
                input.closeEntry();
            }
            output.putNextEntry(new ZipEntry("word/endnotes.xml"));
            output.write(endnotesXml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Files.move(updated, docx, StandardCopyOption.REPLACE_EXISTING);
    }

    private String zipEntry(Path docx, String name) throws Exception {
        try (ZipFile archive = new ZipFile(docx.toFile());
             var input = archive.getInputStream(archive.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
