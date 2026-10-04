package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.parser.ParseLimits;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
/** One bounded real bundled recognition, with unchanged source-space physical box. */
public class OcrScaleProbe38 {
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectory(out);int edge=Integer.parseInt(args[2]);
        if(source.toString().endsWith(".pdf")){var parsed=new PdfLayoutParser().parseForTextExtractionOcr(source,source.getFileName().toString(),ParseLimits.defaults());var images=parsed.pages().get(0).images();if(images.size()!=1)throw new IllegalStateException("One frozen source image required");Path extracted=out.resolve("pdf-source.png");Files.write(extracted,images.get(0).data());source=extracted;}
        var capability=TesseractOcrConverter.detectConfigured();if(!capability.available()||!capability.settings().bundled())throw new IllegalStateException("Bundled OCR required");
        var ocr=new TesseractOcrConverter(DocumentFormat.PNG,capability.settings());long start=System.nanoTime();
        Path prepared;
        if(edge<0){var original=ImageIO.read(source.toFile());double scale=(-edge)/(double)Math.max(original.getWidth(),original.getHeight());var scaled=new BufferedImage((int)Math.round(original.getWidth()*scale),(int)Math.round(original.getHeight()*scale),BufferedImage.TYPE_INT_RGB);var g=scaled.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(original,0,0,scaled.getWidth(),scaled.getHeight(),null);}finally{g.dispose();original.flush();}prepared=out.resolve("prepared.png");ImageIO.write(scaled,"png",prepared.toFile());scaled.flush();}
        else prepared=edge==0?source:OcrImageNormalizer.downscaleForOcr(source,out.resolve("prepared.png"),edge);
        var pixels=ImageIO.read(prepared.toFile());int w=pixels.getWidth(),h=pixels.getHeight();pixels.flush();
        var row=new LinkedHashMap<String,Object>();row.put("inputPixels",List.of(w,h));row.put("edge",edge);row.put("settings",capability.settings());
        if(args.length==4&&args[3].equals("--prepare-only")){row.put("extraOcrInvocations",0);row.put("status","normalizationOnly");new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(out.resolve("model.json").toFile(),row);return;}
        try{
            var physical=new Rect(0,24*25.4/72,480*25.4/72,408*25.4/72);TesseractOcrConverter.RecognitionResult result;
            if(args.length==4){
                // Replay the completed production HTTP TSV; no engine call or truth selection.
                var dimensions=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");var constructor=dimensions.getDeclaredConstructor(int.class,int.class);constructor.setAccessible(true);
                var parser=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dimensions,ParseLimits.class);parser.setAccessible(true);
                var raw=(TesseractOcrConverter.RecognitionResult)parser.invoke(ocr,Path.of(args[3]),1,physical,constructor.newInstance(w,h),ParseLimits.defaults());var original=ImageIO.read(prepared.toFile());
                try{boolean omission=raw.confidence()>=capability.settings().warningConfidence()&&OcrCoverageProbe.hasUncoveredShadedInk(original,raw.blocks(),physical,System.nanoTime()+capability.settings().timeout().toNanos());var refined=raw.blocks().stream().map(b->OcrWordGeometryRefiner.refine(b,original,physical)).toList();result=new TesseractOcrConverter.RecognitionResult(refined,raw.confidence(),raw.wordCount(),false,0,List.of(),omission);}
                finally{original.flush();}row.put("replayedCompletedHttpTsv",args[3]);row.put("extraOcrInvocations",0);
            }else result=ocr.recognizeLayoutResult(prepared,out.resolve("ocr"),1,physical,ParseLimits.defaults());
            row.put("recognition",result);row.put("text",String.join("\n",result.blocks().stream().map(b->b.text()).toList())+ (result.blocks().isEmpty()?"":"\n"));row.put("success",true);}
        catch(ConversionFailureException e){row.put("success",false);row.put("errorCode",e.code());}
        row.put("normalizationAndRecognitionSeconds",(System.nanoTime()-start)/1e9);
        try(var paths=Files.walk(out)){row.put("tsvCount",paths.filter(p->p.toString().endsWith(".tsv")).count());}
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(out.resolve("model.json").toFile(),row);
    }
}
