package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import java.util.*;

/** Model-level counterexample; no image or native OCR completeness claim. */
public class OcrProseTableOrderProbe {
    public static void main(String[] args) {
        var blocks = new ArrayList<TextBlock>();
        var truth = new ArrayList<String>();
        String[] labels = {"Alpha", "Bravo", "Cedar"};
        for (int row=0;row<4;row++) for (int column=0;column<3;column++) {
            truth.add(labels[column]+" keeps record"+row);
            for (int fragment=0;fragment<3;fragment++) {
                String text = fragment==0 ? labels[column] : fragment==1 ? "keeps" : "record"+row;
                var box = new Rect(40+column*330+fragment*70,100+row*100,60,20);
                blocks.add(new TextBlock("r"+row+"c"+column+"f"+fragment,1,box,text,box.bottom(),
                    null,0,0,blocks.size(),List.of(),Transform2D.IDENTITY,
                    List.of(new TextBlock.OcrWord(box,text,.92))));
            }
        }
        var snapshot = List.copyOf(blocks);
        var result = OcrReadingOrder.arrange(blocks,1000,System.nanoTime()+1_000_000_000);
        if (!snapshot.equals(blocks)) throw new AssertionError("Source blocks changed");
        for (int i=0;i<blocks.size();i++)
            if (snapshot.get(i)!=blocks.get(i)) throw new AssertionError("Source object changed");
        System.out.println("ADJUSTED="+result.adjusted());
        System.out.println("TRUTH="+String.join("|",truth));
        System.out.println("ORIGINAL="+String.join("|",blocks.stream().map(TextBlock::text).toList()));
        System.out.println("OUTPUT="+String.join("|",result.lines()));
    }
}
