package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OcrReadingOrderTest {
    @Test
    void separatesMergedRowsAndPreservesEveryOriginalCharacterNumberAndBox() {
        var rows = rows(false); var snapshot = List.copyOf(rows);
        var result = arrange(rows);
        assertTrue(result.adjusted());
        assertEquals(List.of("Shipment number 00462", "Shipment number 00463", "Shipment number 00464",
                "Reviewing debit -307.16", "Reviewing debit -308.16", "Reviewing debit -309.16"), result.lines());
        assertEquals(snapshot, rows);
    }

    @Test
    void retainsAlreadyCorrectColumnOrderAndSplitsChineseSentences() {
        List<TextBlock> correct = new ArrayList<>();
        for (int side = 0; side < 2; side++) for (int i = 0; i < 3; i++)
            correct.add(block(List.of(word(side == 0 ? "第一栏保留数字00462。" : "第二栏必须核对307.16。", side*600+50, 100+i*100, 300))));
        assertFalse(arrange(correct).adjusted());
        var merged = new ArrayList<TextBlock>();
        for (int i = 0; i < 3; i++) merged.add(block(List.of(word("第一栏保留数字00462。",50,100+i*100,300),word("第二栏必须核对307.16。",650,100+i*100,300))));
        assertTrue(arrange(merged).adjusted());
    }

    @Test
    void ambiguousTablesHeadersNarrowGuttersAndInsufficientRowsRetainEngineOrder() {
        assertFalse(arrange(rows(true)).adjusted());
        var header = rows(false); header.add(0, block(List.of(word("Document spanning title crosses both columns", 50, 50, 900))));
        assertFalse(arrange(header).adjusted());
        assertFalse(arrange(rows(false).subList(0,2)).adjusted());
        assertFalse(OcrReadingOrder.arrange(rows(false),1000,System.nanoTime()-1).adjusted());
        List<TextBlock> narrow = new ArrayList<>();
        for (int i = 0; i < 3; i++) narrow.add(block(List.of(word("First independent sentence",50,100+i*100,430),
                word("Second independent sentence",510,100+i*100,430))));
        assertFalse(arrange(narrow).adjusted());
    }

    @Test
    void rejectsMissingWordInventoryAndBoundsDenseWork() {
        var rows = rows(false); var b = rows.get(0);
        rows.set(0,new TextBlock(b.id(),1,b.box(),b.text()+" unseen",b.baselineY(),null,0,0,0,List.of(),Transform2D.IDENTITY,b.ocrWords()));
        assertFalse(arrange(rows).adjusted());
        assertFalse(arrange(Collections.nCopies(501, rows.get(0))).adjusted());
    }

    private static OcrReadingOrder.Result arrange(List<TextBlock> blocks) {
        return OcrReadingOrder.arrange(blocks,1000,System.nanoTime()+1_000_000_000);
    }
    private static ArrayList<TextBlock> rows(boolean table) {
        var rows = new ArrayList<TextBlock>();
        for (int i=0;i<3;i++) rows.add(block(table
                ? List.of(word("Amount",50,100+i*100,80),word("00462",650,100+i*100,80))
                : List.of(word("Shipment",50,100+i*100,100),word("number",170,100+i*100,90),word("0046"+(i+2),280,100+i*100,80),
                          word("Reviewing",650,100+i*100,90),word("debit",750,100+i*100,70),word("-30"+(i+7)+".16",840,100+i*100,100))));
        return rows;
    }
    private static TextBlock.OcrWord word(String text,double x,double y,double width) { return new TextBlock.OcrWord(new Rect(x,y,width,20),text,.90); }
    private static TextBlock block(List<TextBlock.OcrWord> words) {
        Rect box = words.get(0).box();for (var w:words) box=box.union(w.box());
        return new TextBlock("line",1,box,OcrDeskewSelection.join(words),box.bottom(),null,0,0,0,List.of(),Transform2D.IDENTITY,words);
    }
}
