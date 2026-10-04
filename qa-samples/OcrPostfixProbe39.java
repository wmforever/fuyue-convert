package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import java.nio.file.*;
import java.util.*;
/** Calls actual production numeric matching and exposes literal tokens for negative controls. */
public class OcrPostfixProbe39 {
    public static void main(String[] args)throws Exception {
        var mapper=new ObjectMapper();var truth=mapper.readTree(Path.of(args[0]).toFile());var rows=new ArrayList<Object>();
        var method=OcrTextDeduplicator.class.getDeclaredMethod("numbers",String.class);method.setAccessible(true);
        for(var p:truth.get("javaPairs")){
            String first=p.get("native").asText(),second=p.get("ocr").asText();var a=text(first);var b=text(second);
            boolean dup=OcrTextDeduplicator.duplicates(b,List.of(a)),reverse=OcrTextDeduplicator.duplicates(a,List.of(b)),conflict=OcrTextDeduplicator.numericConflict(b,List.of(a));
            var nativeTokens=method.invoke(null,first);var ocrTokens=method.invoke(null,second);
            if(args.length>2){if(dup!=p.get("expectedDuplicate").asBoolean()||reverse!=dup||conflict!=p.get("expectedConflict").asBoolean())throw new IllegalStateException(p.get("id").asText()+":incorrect matching");if(p.has("expectedNativeTokens")&&!mapper.valueToTree(nativeTokens).equals(p.get("expectedNativeTokens")))throw new IllegalStateException(p.get("id").asText()+":range/list/field token changed");}
            var row=new LinkedHashMap<String,Object>();row.put("id",p.get("id").asText());row.put("native",first);row.put("ocr",second);row.put("duplicate",dup);row.put("reverseDuplicate",reverse);row.put("numericConflict",conflict);row.put("nativeTokens",nativeTokens);row.put("ocrTokens",ocrTokens);rows.add(row);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),Map.of("rows",rows,"productionMatcherCalls",rows.size()*3,"recognitionInvocations",0));System.out.println("pairs="+rows.size()+"productionMatcherCalls="+rows.size()*3);
    }
    private static TextBlock text(String s){return new TextBlock(s,1,new Rect(10,10,120,8),s,18,FontStyle.defaults(),0);}
}
