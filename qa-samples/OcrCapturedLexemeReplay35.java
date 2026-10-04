package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.*;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Parse captured production TSVs and immutable OFDs; zero engine invocations. */
public class OcrCapturedLexemeReplay35 {
    public static void main(String[] args) throws Exception {
        Path corpus=Path.of(args[0]),report=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectory(out);
        var mapper=new ObjectMapper();var http=mapper.readTree(report.resolve("report.json").toFile());
        var capture=mapper.readTree(report.resolve("ocr-capture.json").toFile());var rows=new ArrayList<Object>();
        var limits=ParseLimits.defaults();
        var ocr=new TesseractOcrConverter(DocumentFormat.PNG,new TesseractOcrConverter.Settings(Path.of("/bin/true"),"eng","captured-only"));
        var dimensionsClass=Class.forName(TesseractOcrConverter.class.getName()+"$ImageDimensions");
        var constructor=dimensionsClass.getDeclaredConstructor(int.class,int.class);constructor.setAccessible(true);
        var parse=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dimensionsClass,ParseLimits.class);parse.setAccessible(true);
        for(var item:http.get("cases")) {
            String id=item.get("case").asText().replaceFirst("-text$","");if(!Files.exists(corpus.resolve(id+".ofd")))continue;
            String task=item.get("task").get("taskId").asText();com.fasterxml.jackson.databind.JsonNode selected=null;
            for(var saved:capture.get("records")) if(saved.get("taskId").asText().equals(task)
                    && (selected==null || saved.get("bytes").asInt()>selected.get("bytes").asInt()))selected=saved;
            if(selected==null)throw new IllegalStateException("Missing captured TSV: "+id);
            Path tsv=report.resolve(selected.get("artifact").asText());if(!sha(Files.readAllBytes(tsv)).equals(selected.get("sha256").asText()))throw new IllegalStateException("TSV hash changed");
            var parsed=new OfdrwParser().parse(new SafeOfdExtractor().extract(corpus.resolve(id+".ofd"),out.resolve(id),limits),id+".ofd",limits);
            var page=parsed.pages().get(0);var image=page.images().get(0);var pixels=ImageIO.read(new ByteArrayInputStream(image.data()));
            var full=(TesseractOcrConverter.RecognitionResult)parse.invoke(ocr,tsv,1,image.box(),constructor.newInstance(pixels.getWidth(),pixels.getHeight()),limits);
            ocr.requireUsableResult(full,id);
            for(var block:full.blocks()) if(!block.equals(OcrWordGeometryRefiner.refine(block,pixels,image.box())))throw new IllegalStateException("Unexpected geometry refinement");
            var nativeText=page.textBlocks();var novel=full.blocks().stream().filter(b->!OcrTextDeduplicator.duplicates(b,nativeText)).toList();
            var all=new ArrayList<>(nativeText);var additions=new ArrayList<TextBlock>();
            for(var block:novel) {
                if(OcrTextDeduplicator.duplicates(block,all))continue;
                var addition=new TextBlock(block.id()+"-i1",block.pageNumber(),block.box(),block.text(),block.baselineY(),block.style(),additions.size()+1,
                        block.textOffsetXmm(),block.textOffsetYmm(),block.advancesMm(),block.transform(),block.ocrWords());additions.add(addition);all.add(addition);
            }
            var unionPage=new PageModel(1,page.physicalBox(),all,List.of(),List.of(),List.of(),List.of(),List.of());
            String assembled=OfdToTextConverter.text(List.of(new PageLayoutAnalyzer().analyze(unionPage)));
            if(!assembled.equals(Files.readString(report.resolve(item.get("artifact").asText()))))throw new IllegalStateException("Actual HTTP/replay mismatch: "+id);
            var row=new LinkedHashMap<String,Object>();row.put("case",id);row.put("native",nativeText);row.put("fullRecognition",full);row.put("beyondNative",novel);
            row.put("additions",additions);row.put("union",all);row.put("assembled",assembled);row.put("numericConflict",full.blocks().stream().anyMatch(b->OcrTextDeduplicator.numericConflict(b,nativeText)));
            row.put("physicalBox",page.physicalBox());row.put("imageBox",image.box());row.put("imageResourceSha256",sha(image.data()));row.put("width",pixels.getWidth());row.put("height",pixels.getHeight());
            row.put("tsvSha256",sha(Files.readAllBytes(tsv)));row.put("refinementIdentity",true);row.put("actualHttpTextExact",true);rows.add(row);pixels.flush();
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("replay.json").toFile(),Map.of("rows",rows,"recognitionInvocations",0));
        System.out.println("replayed="+rows.size()+" recognitionInvocations=0 actualHttpExact=true");
    }
    private static String sha(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
}
