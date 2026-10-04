package com.fuyue.formatconverter.task;
import com.fuyue.formatconverter.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** Actual package-private production calls, never a source-only regex simulation. */
public class OcrNumericLexemeProbe35 {
    public static void main(String[] args)throws Exception {
        var rows=new ArrayList<Object>();
        String[][] pairs={{"accounting","Amount 048.65","Amount (048.65)"},
            {"percent","Rate 10","Rate 10%"},{"per-mille","Rate 10","Rate 10‰"},
            {"dollar","Amount 048.65","Amount $048.65"},{"euro","Amount 048.65","Amount 048.65€"},
            {"currency-accounting","Amount (048.65)","Amount $(048.65)"},
            {"ordinary-punctuation","Invoice No 2076","Invoice No. 2076"},
            {"same-marked-value","Rate 10%","Rate 10%"},
            {"ids","Record 00793","Record 007930"},{"minus","Amount 048.65","Amount -048.65"},
            {"decimal","Amount 04865","Amount 048.65"},{"separate-numbers","Values 12 34","Values 1234"},
            {"space-after-sign","Amount 048.65","Amount - 048.65"}};
        for(var p:pairs) {
            if(args.length>1 && !p[0].equals(args[1]))continue;
            var a=text(p[1]);var b=text(p[2]);rows.add(Map.of("id",p[0],"native",p[1],"ocr",p[2],
                "duplicate",OcrTextDeduplicator.duplicates(b,List.of(a)),"reverseDuplicate",OcrTextDeduplicator.duplicates(a,List.of(b)),
                "numericConflict",OcrTextDeduplicator.numericConflict(b,List.of(a))));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),Map.of("cases",rows,"productionCalls",rows.size()*3));
        rows.forEach(System.out::println);
    }
    private static TextBlock text(String value) {return new TextBlock(value,1,new Rect(10,10,100,8),value,18,FontStyle.defaults(),0);}
}
