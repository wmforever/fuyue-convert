package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Trace actual parser/OCR/dedup stages; recognize each unique raster/box once. */
public class OcrSparseTraceProbe {
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        Path corpus=Path.of(args[0]),out=Path.of(args[1]); Files.createDirectory(out);
        var mapper=new ObjectMapper(); var manifest=mapper.readTree(corpus.resolve("expected.json").toFile());
        var limits=ParseLimits.defaults(); var capability=TesseractOcrConverter.detectConfigured();
        if(!capability.available() || !capability.settings().bundled()) throw new IllegalStateException("Bundled required");
        var ocr=new TesseractOcrConverter(DocumentFormat.PNG,capability.settings());
        var cache=new HashMap<String,TesseractOcrConverter.RecognitionResult>(); var rows=new ArrayList<Object>();
        for(var name:manifest.get("ofds")) {
            String id=name.asText().replace(".ofd",""); Path work=Files.createDirectory(out.resolve(id));
            var parsed=new OfdrwParser().parse(new SafeOfdExtractor().extract(corpus.resolve(name.asText()),work.resolve("extract"),limits),id,limits);
            var page=parsed.pages().get(0); var image=page.images().get(0); byte[] data=image.data();
            var pixels=ImageIO.read(new ByteArrayInputStream(data));
            Path raster=work.resolve("normalized.png"); ImageIO.write(pixels,"png",raster.toFile());
            var digest=MessageDigest.getInstance("SHA-256");
            var line=ByteBuffer.allocate(pixels.getWidth()*4);
            for(int y=0;y<pixels.getHeight();y++) {
                line.clear(); for(int x=0;x<pixels.getWidth();x++)line.putInt(pixels.getRGB(x,y)); digest.update(line.array());
            }
            String pixelSha=HexFormat.of().formatHex(digest.digest());
            String key=pixelSha+image.box(); boolean cached=cache.containsKey(key);
            var result=cache.get(key);
            if(result==null) { result=ocr.recognizeLayoutResult(raster,work.resolve("ocr"),1,image.box(),limits); cache.put(key,result); }
            var row=new LinkedHashMap<String,Object>(); row.put("case",id);row.put("nativeBlocks",page.textBlocks());
            row.put("imageBox",image.box());row.put("physicalBox",page.physicalBox());row.put("width",pixels.getWidth());row.put("height",pixels.getHeight());
            row.put("resourceSha256",sha(data));row.put("normalizedPngSha256",sha(Files.readAllBytes(raster)));row.put("pixelSha256",pixelSha);
            row.put("cachedRecognition",cached);row.put("requiredImages",ScannedContentDetector.imagesRequiringOcr(page.textBlocks(),page.images(),page.physicalBox()).size());
            row.put("parserWarnings",page.warnings());row.put("recognition",result);
            var duplicates=new ArrayList<Object>();
            for(var block:result.blocks())duplicates.add(Map.of("text",block.text(),"box",block.box(),"duplicateNative",OcrTextDeduplicator.duplicates(block,page.textBlocks()),
                "matchingNative",page.textBlocks().stream().filter(n->OcrTextDeduplicator.duplicates(block,List.of(n))).toList()));
            row.put("deduplication",duplicates);
            try { ocr.requireUsableResult(result,id);row.put("usable",true); } catch(ConversionFailureException e){row.put("usable",false);row.put("unusableCode",e.code());}
            rows.add(row);pixels.flush();
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("trace.json").toFile(),Map.of("rows",rows,"uniqueRecognitions",cache.size()));
        System.out.println("cases="+rows.size()+" uniqueRecognitions="+cache.size());
    }
}
