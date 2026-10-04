package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import java.nio.file.*;
import java.util.*;

/** Replay observed full OCR/native models; never invokes or retunes OCR. */
public class OcrDedupReplay32 {
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();var rows=new ArrayList<Object>();
        for(int i=1;i<args.length;i++) {
            var source=mapper.readTree(Path.of(args[i]).toFile());
            for(var row:source.get("rows")) {
                if(i>1 && !row.get("case").asText().equals("original"))continue;
                var nativeText=mapper.convertValue(row.get("nativeBlocks"),new TypeReference<List<TextBlock>>(){});
                var full=mapper.convertValue(row.get("recognition"),TesseractOcrConverter.RecognitionResult.class);
                var novel=full.blocks().stream().filter(b->!OcrTextDeduplicator.duplicates(b,nativeText)).toList();
                var all=new ArrayList<>(nativeText);var additions=new ArrayList<TextBlock>();
                for(var block:novel) {
                    if(OcrTextDeduplicator.duplicates(block,all))continue;
                    var addition=new TextBlock(block.id()+"-i1",block.pageNumber(),block.box(),block.text(),block.baselineY(),
                        block.style(),additions.size()+1,block.textOffsetXmm(),block.textOffsetYmm(),block.advancesMm(),block.transform(),block.ocrWords());
                    additions.add(addition);all.add(addition);
                }
                boolean conflict=full.blocks().stream().anyMatch(b->OcrTextDeduplicator.numericConflict(b,nativeText));
                var physical=mapper.convertValue(row.get("physicalBox"),Rect.class);
                var page=new PageModel(1,physical,all,List.of(),List.of(),List.of(),List.of(),List.of());
                String assembled=OfdToTextConverter.text(List.of(new PageLayoutAnalyzer().analyze(page)));
                rows.add(Map.of("case",i==1?row.get("case").asText():"original-iteration30","native",nativeText,"fullRecognition",full,
                    "beyondNative",novel,"additions",additions,"union",all,"numericConflict",conflict,
                    "strictNoNewText",row.get("requiredImages").asInt()>0 && novel.isEmpty(),"assembled",assembled));
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),Map.of("rows",rows,"recognitionInvocations",0));
        System.out.println("replayed="+rows.size()+" recognitionInvocations=0");
    }
}
