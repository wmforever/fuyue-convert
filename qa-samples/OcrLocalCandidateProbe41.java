package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;

/** No native recognition: prepare exact images or replay actual current/old selectors. */
public final class OcrLocalCandidateProbe41 {
    static final ObjectMapper JSON = new ObjectMapper();
    static Map<String,Object> prepare(BufferedImage source, BufferedImage image, Path path) throws Exception {
        if (image == null) return Map.of("available",false);
        if (image.getWidth()!=source.getWidth() || image.getHeight()!=source.getHeight()) throw new AssertionError("resampling");
        ImageIO.write(image,"png",path.toFile()); image.flush();
        return Map.of("available",true,"dimensionsUnchanged",true);
    }
    static Method method(String name, Class<?>... types) throws Exception {
        Method m=TesseractOcrConverter.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;
    }
    public static void main(String[] args) throws Exception {
        String mode=args[0];Path input=Path.of(args[1]),out=Path.of(args[2]),runtime=Path.of(args[3]);
        var image=ImageIO.read(input.toFile());var report=new LinkedHashMap<String,Object>();
        if (mode.startsWith("prepare")) {
            long started=System.nanoTime();
            report.put("current",prepare(image,OcrContrastEnhancer.enhance(image),out.resolve("enhanced.png")));
            if (mode.equals("prepare-current")) report.put("local",prepare(image,OcrLocalContrastCandidate41.enhance(image),out.resolve("local.png")));
            report.put("seconds",(System.nanoTime()-started)/1e9);
        } else {
            int w=image.getWidth(),h=image.getHeight();Rect page=new Rect(0,0,w*25.4/300,h*25.4/300);
            var converter=new TesseractOcrConverter(DocumentFormat.PNG,runtime.resolve("bin/tesseract"),"chi_sim+eng",Duration.ofSeconds(120));
            Class<?> dims=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");
            Constructor<?> constructor=dims.getDeclaredConstructor(int.class,int.class);constructor.setAccessible(true);
            Method parse=method("parseTsv",Path.class,int.class,Rect.class,dims,ParseLimits.class);
            var original=(TesseractOcrConverter.RecognitionResult)parse.invoke(converter,out.resolve("original.tsv"),1,page,constructor.newInstance(w,h),ParseLimits.defaults());
            report.put("original",original);report.put("physicalBounds",page);
            boolean current=mode.equals("evaluate-current");boolean eligible=true,stable=true;
            if(current) {
                long deadline=System.nanoTime()+5_000_000_000L;
                boolean uncovered=OcrCoverageProbe.hasUncoveredShadedInk(image,original.blocks(),page,deadline);
                Method geometry=method("originalGeometryStable",TesseractOcrConverter.RecognitionResult.class,BufferedImage.class,Rect.class,long.class);
                stable=(boolean)geometry.invoke(null,original,image,page,deadline);
                eligible=original.blocks().isEmpty()||original.confidence()<.75||uncovered&&original.confidence()<=.95;
                report.put("uncoveredShadedInk",uncovered);report.put("originalGeometryStable",stable);
            }
            report.put("productionRetryEligible",eligible);report.put("earlyEligibilityNotSimulated",!current);
            var variants=new LinkedHashMap<String,Object>();
            for(String name:List.of("enhanced","local")) {
                Path tsv=out.resolve(name+".tsv");if(!Files.exists(tsv))continue;
                var candidate=(TesseractOcrConverter.RecognitionResult)parse.invoke(converter,tsv,1,page,constructor.newInstance(w,h),ParseLimits.defaults());
                boolean full=(boolean)method("preferEnhanced",original.getClass(),candidate.getClass(),double.class).invoke(null,original,candidate,.35);
                Object selected=full&&eligible?candidate:original;boolean partial=false;
                if(current&&!full&&eligible&&stable) {
                    var recovery=OcrPartialRecovery.select(original,candidate,page,.35,.05,true,0,System.nanoTime()+5_000_000_000L);
                    partial=recovery.partialRecovery();if(partial)selected=recovery;
                }
                boolean bounds=candidate.blocks().stream().allMatch(b->page.contains(new Point(b.box().x(),b.box().y()),.01)
                    &&page.contains(new Point(b.box().right(),b.box().bottom()),.01)
                    &&b.ocrWords().stream().allMatch(word->page.contains(new Point(word.box().x(),word.box().y()),.01)
                        &&page.contains(new Point(word.box().right(),word.box().bottom()),.01)));
                variants.put(name,Map.of("candidate",candidate,"selected",selected,"fullActualAccepted",full,
                    "fullActualEligibleAccepted",full&&eligible,"partialActualEligibleAccepted",partial,"candidateBoundsInsideOriginal",bounds,
                    "candidateGain",candidate.confidence()-original.confidence(),"selectedOriginal",selected==original));
            }
            report.put("variants",variants);report.put("extraNativeRecognition",0);
        }
        image.flush();JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve(mode+".json").toFile(),report);
    }
}
