package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OcrFragmentedColumnsTest {
    @Test void reconstructsOnlyRepeatedAlignedFragmentsAndPreservesSourceObjects() {
        var blocks = fragments("keeps", false);
        var snapshot = List.copyOf(blocks);
        var arranged = arrange(blocks);
        assertTrue(arranged.adjusted());
        assertTrue(arranged.multipleColumns());
        assertEquals(expected("keeps"), arranged.lines());
        assertEquals(snapshot, blocks);
        for (int i=0;i<blocks.size();i++) assertSame(snapshot.get(i), blocks.get(i));
    }

    @Test void retainsExactDecimalSignCurrencyFullwidthAndSeparatedNumericLexemes() {
        for (String token : List.of("USD .95", "debit -.75", "dated 2026-11-28", "ref AB-00562", "金额应 １２．９５")) {
            var blocks = fragments(token, false);
            var arranged = arrange(blocks);
            assertTrue(arranged.adjusted(), token);
            assertEquals(expected(token), arranged.lines());
            assertEquals(12, arranged.lines().stream().filter(line -> line.contains(token)).count());
        }
    }

    @Test void ambiguousTablesSpanningRowsMissingWordsAndDenseOrExpiredWorkRetainEngineOrder() {
        var tables = fragments("1234", true); assertUnchanged(tables);
        var header = fragments("keeps", false);
        header.add(0, block("Spanning title crosses all columns", 40, 30, 900)); assertUnchanged(header);
        var absent = fragments("keeps", false); var b=absent.get(0);
        absent.set(0,new TextBlock(b.id(),1,b.box(),b.text()+" unseen",b.baselineY(),null,0,0,0,List.of(),Transform2D.IDENTITY,b.ocrWords()));
        assertUnchanged(absent);
        var close = fragments("keeps", false);
        close.set(1,block("Column",40,115,60)); assertUnchanged(close);
        assertUnchanged(Collections.nCopies(501, close.get(0)));
        assertFalse(OcrReadingOrder.arrange(fragments("keeps",false),1000,System.nanoTime()-1).adjusted());
    }

    @Test void narrowGuttersMergedColumnRowsAndAlreadyCompleteLinesRetainEngineOrder() {
        var narrow = new ArrayList<TextBlock>();
        for (int side=0;side<3;side++) for (int row=0;row<4;row++)
            for (int fragment=0;fragment<3;fragment++) narrow.add(block("part",40+side*260+fragment*80,100+row*100,70));
        assertUnchanged(narrow);
        var merged = new ArrayList<TextBlock>();
        for(int row=0;row<4;row++) merged.add(block("Column keeps sentence",40,100+row*100,900));
        assertUnchanged(merged);
        var correct = new ArrayList<TextBlock>();
        for(int side=0;side<3;side++)for(int row=0;row<4;row++)correct.add(block("Column keeps sentence",40+side*330,100+row*100,200));
        assertUnchanged(correct);
    }

    @Test void disjointVerticalSectionsRetainCorrectTopToBottomEngineOrder() {
        var sections = new ArrayList<TextBlock>();
        // Correct input order: upper section on the right, then middle on the
        // left, then bottom in the center. Horizontal gutters alone are not columns.
        double[] x = {700, 40, 370}, y = {100, 400, 800};
        String[] labels = {"Upper", "Middle", "Bottom"};
        for (int section=0;section<3;section++) for (int row=0;row<3;row++) {
            String[] pieces = {labels[section], "section", "sentence"+row};
            for (int part=0;part<3;part++)
                sections.add(block(pieces[part],x[section]+part*70,y[section]+row*100,60));
        }
        assertEquals(27,sections.size());
        var arranged = arrange(sections);
        assertFalse(arranged.adjusted(), () -> String.join("\n",arranged.lines()));
        assertEquals(sections.stream().map(TextBlock::text).toList(),arranged.lines());
    }

    @Test void reconstructsDistinctColumnTextInLeftToRightThenTopToBottomOrder() {
        var blocks = distinctColumns(0);
        var snapshot = List.copyOf(blocks);
        var result = arrange(blocks);
        assertTrue(result.adjusted());
        assertEquals(distinctExpected(), result.lines());
        assertEquals(snapshot, blocks);
        for (int i=0;i<blocks.size();i++) assertSame(snapshot.get(i),blocks.get(i));
    }

    @Test void commonVerticalCoverageRequiresAtLeastSixtyPercent() {
        // Each column spans 200. With first-to-last offsets of 50, the
        // common span is 150 and the combined span is 250: exactly 60%.
        for (double offset : new double[]{49.9, 50.0}) {
            var result = arrange(distinctColumns(offset));
            assertTrue(result.adjusted(), "accepted coverage at offset " + offset);
            assertEquals(distinctExpected(),result.lines());
        }
        // Just below 60%, and more strongly staggered short columns, retain
        // engine order even when horizontal gutters and fragments qualify.
        for (double offset : new double[]{50.1, 125.0}) assertUnchanged(distinctColumns(offset));
    }

    private static ArrayList<TextBlock> distinctColumns(double firstToLastOffset) {
        var blocks = new ArrayList<TextBlock>();
        String[] labels = {"Alpha", "Bravo", "Cedar"};
        for (int side=0;side<3;side++) for (int part=0;part<3;part++) for (int row=0;row<3;row++) {
            String text = part==0 ? labels[side] : part==1 ? "keeps" : "sentence"+row;
            blocks.add(block(text,40+side*330+part*70,100+row*100+side*firstToLastOffset/2,60));
        }
        return blocks;
    }

    private static List<String> distinctExpected() {
        var lines = new ArrayList<String>();
        for (String label : List.of("Alpha", "Bravo", "Cedar"))
            for (int row=0;row<3;row++) lines.add(label+" keeps sentence"+row);
        return lines;
    }

    private static List<String> expected(String token) {
        return java.util.stream.IntStream.range(0,12).mapToObj(i -> "Column " + token + " sentence").toList();
    }
    private static ArrayList<TextBlock> fragments(String token, boolean table) {
        var blocks=new ArrayList<TextBlock>();
        for(int side=0;side<3;side++)for(int part=0;part<3;part++)for(int row=0;row<4;row++) {
            String text=part==0?"Column":part==1?token:"sentence";
            if(table && part==2)text="2026-11-28";
            blocks.add(block(text,40+side*330+part*70,100+row*100,60));
        }
        return blocks;
    }
    private static TextBlock block(String text,double x,double y,double width) {
        var box=new Rect(x,y,width,20);
        var words=Arrays.stream(text.split(" ")).map(t -> new TextBlock.OcrWord(box,t,.92)).toList();
        return new TextBlock("block-"+x+"-"+y,1,box,text,box.bottom(),null,0,0,0,List.of(),Transform2D.IDENTITY,words);
    }
    private static OcrReadingOrder.Result arrange(List<TextBlock> blocks) {
        return OcrReadingOrder.arrange(blocks,1000,System.nanoTime()+1_000_000_000);
    }
    private static void assertUnchanged(List<TextBlock> blocks) {
        var result=arrange(blocks);assertFalse(result.adjusted());
        assertEquals(blocks.stream().map(TextBlock::text).toList(),result.lines());
    }
}
