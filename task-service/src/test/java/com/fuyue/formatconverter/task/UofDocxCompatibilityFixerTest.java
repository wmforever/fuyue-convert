package com.fuyue.formatconverter.task;

import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
