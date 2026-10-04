package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.lang.reflect.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Diagnostic only: invokes the actual accepted production selectors, never truth. */
public class OcrPsmCandidateProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static Method parse, joined, boundary, stable, comparison, reliable;
    static Constructor<?> dimensions;
    static TesseractOcrConverter converter;

    static Method method(String name, Class<?>... types) throws Exception {
        Method result=TesseractOcrConverter.class.getDeclaredMethod(name,types);
        result.setAccessible(true); return result;
    }
    static TesseractOcrConverter.RecognitionResult read(Path path,Rect page,int w,int h) throws Exception {
        return (TesseractOcrConverter.RecognitionResult)parse.invoke(converter,path,1,page,
                dimensions.newInstance(w,h),ParseLimits.defaults());
    }
    static boolean preserved(TesseractOcrConverter.RecognitionResult original,
                             TesseractOcrConverter.RecognitionResult candidate,boolean numeric) throws Exception {
        String text=(String)joined.invoke(null,candidate);
        String next=numeric?text.toLowerCase(Locale.ROOT):(String)reliable.invoke(null,text);
        int offset=0;
        for (var block:original.blocks()) for (var word:block.ocrWords()) {
            if (word.confidence()<.85) continue;
            if (numeric && word.text().codePoints().noneMatch(Character::isDigit)) continue;
            String old=numeric?word.text().toLowerCase(Locale.ROOT):(String)reliable.invoke(null,word.text());
            if (old.isEmpty()) continue;
            int position=next.indexOf(old,offset);
            while(position>=0 && (boolean)boundary.invoke(null,next,old,position)) position=next.indexOf(old,position+1);
            if(position<0) return false;
            offset=position+old.length();
        }
        return true;
    }
    static boolean inside(TesseractOcrConverter.RecognitionResult result,Rect page) {
        return result.blocks().stream().allMatch(b -> inside(b.box(),page)
                && b.ocrWords().stream().allMatch(w -> inside(w.box(),page)));
    }
    static boolean inside(Rect box,Rect page) {
        return Double.isFinite(box.x()) && Double.isFinite(box.y()) && Double.isFinite(box.width())
                && Double.isFinite(box.height()) && page.contains(new Point(box.x(),box.y()),.01)
                && page.contains(new Point(box.right(),box.bottom()),.01);
    }
    static Map<String,Object> assess(TesseractOcrConverter.RecognitionResult original,
            TesseractOcrConverter.RecognitionResult candidate,Rect page,boolean geometry,boolean eligible,
            double remainingSeconds) throws Exception {
        long deadline=System.nanoTime()+(long)(Math.max(0,remainingSeconds)*1e9);
        boolean full=TesseractOcrConverter.preferEnhanced(original,candidate,.35);
        var partial=OcrPartialRecovery.select(original,candidate,page,.35,.05,true,0,deadline);
        boolean deadlineGate=remainingSeconds>0 && System.nanoTime()<deadline;
        var raw=full?new TesseractOcrConverter.RecognitionResult(candidate.blocks(),candidate.confidence(),candidate.wordCount(),true)
                :partial.partialRecovery() && geometry?partial:original;
        var selected=eligible && deadlineGate?raw:original;
        String previous=(String)comparison.invoke(null,original), next=(String)comparison.invoke(null,candidate);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("candidate",candidate);report.put("selectorResultIgnoringEligibility",raw);report.put("selected",selected);
        report.put("candidateGain",candidate.confidence()-original.confidence());
        report.put("minimumGate",!candidate.blocks().isEmpty() && candidate.confidence()>=.35);
        report.put("gainGate",candidate.confidence()+1e-9>=original.confidence()+.05);
        report.put("contentLengthGate",!next.isEmpty() && next.codePointCount(0,next.length())>=previous.codePointCount(0,previous.length())*.90);
        report.put("reliableNumericSurfacesPreserved",preserved(original,candidate,true));
        report.put("reliableWordsOrderedPreserved",preserved(original,candidate,false));
        report.put("boundsInsideOriginalImageCoordinates",inside(candidate,page));
        report.put("fullActualAccepted",full);report.put("partialActualBeforeGeometry",partial.partialRecovery());
        report.put("partialAccepted",!full && selected.partialRecovery());
        report.put("originalBlocksExactIfPartial",!selected.partialRecovery() || original.blocks().stream()
                .allMatch(b -> selected.blocks().stream().anyMatch(n -> n==b)));
        report.put("remainingOriginalBudgetSeconds",remainingSeconds);report.put("deadlineGate",deadlineGate);
        report.put("adoption",selected==original?"original":full?"full":"partial");
        report.put("candidateRows",candidate.blocks().stream().map(b -> Map.of("text",b.text(),
                "letterCount",b.text().codePoints().filter(Character::isLetter).count(),
                "averageConfidence",b.ocrWords().stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0),
                "everyWordMinimum",b.ocrWords().stream().allMatch(w -> w.confidence()>=.35),
                "rowGainGate",b.ocrWords().stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0)
                    >=Math.max(.35,original.confidence()+.05))).toList());
        return report;
    }
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[1]),out=Path.of(args[2]),runtime=Path.of(args[3]);
        Files.createDirectories(out);
        var image=ImageIO.read(source.toFile()); int w=image.getWidth(),h=image.getHeight();
        Rect page=new Rect(0,0,w*25.4/300,h*25.4/300);
        if(args[0].equals("prepare")) {
            long start=System.nanoTime();var enhanced=OcrContrastEnhancer.enhance(image);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("seconds",(System.nanoTime()-start)/1e9);report.put("width",w);report.put("height",h);
            report.put("enhancedAvailable",enhanced!=null);
            if(enhanced!=null) {
                report.put("dimensionsUnchanged",enhanced.getWidth()==w && enhanced.getHeight()==h);
                ImageIO.write(enhanced,"png",out.resolve("enhanced.png").toFile());enhanced.flush();
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("preparation.json").toFile(),report);return;
        }
        converter=new TesseractOcrConverter(DocumentFormat.PNG,runtime.resolve("bin/tesseract"),"chi_sim+eng",Duration.ofSeconds(120));
        Class<?> dims=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");
        dimensions=dims.getDeclaredConstructor(int.class,int.class);dimensions.setAccessible(true);
        parse=method("parseTsv",Path.class,int.class,Rect.class,dims,ParseLimits.class);
        joined=method("joinedText",TesseractOcrConverter.RecognitionResult.class);
        boundary=method("numericBoundaryChanged",String.class,String.class,int.class);
        stable=method("originalGeometryStable",TesseractOcrConverter.RecognitionResult.class,java.awt.image.BufferedImage.class,Rect.class,long.class);
        comparison=method("comparisonText",TesseractOcrConverter.RecognitionResult.class);reliable=method("reliableText",String.class);
        var original=read(out.resolve("original.tsv"),page,w,h);
        long eligibilityDeadline=System.nanoTime()+120_000_000_000L;
        boolean geometry=(boolean)stable.invoke(null,original,image,page,eligibilityDeadline);
        boolean uncovered=OcrCoverageProbe.hasUncoveredShadedInk(image,original.blocks(),page,eligibilityDeadline);
        boolean eligible=original.blocks().isEmpty() || original.confidence()<.75 || uncovered && original.confidence()<=.95;
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("original",original);report.put("originalPhysicalBounds",page);
        report.put("originalGeometryStable",geometry);report.put("uncoveredShadedInk",uncovered);
        report.put("productionRetryEligible",eligible);report.put("productionPsm",3);
        var variants=new LinkedHashMap<String,Object>();
        for(int i=0;i<2;i++) {
            String variant=i==0?"enhanced-psm3":"enhanced-psm6";
            Path path=out.resolve(variant+".tsv");if(!Files.exists(path))continue;
            variants.put(variant,assess(original,read(path,page,w,h),page,geometry,eligible,Double.parseDouble(args[4+i])));
        }
        report.put("variants",variants);
        // The proposed candidate may be considered only after existing PSM3 rejected, never replacing an accepted retry.
        var psm3=variants.get("enhanced-psm3");var psm6=variants.get("enhanced-psm6");
        Object selected=original;String adoption="original";
        if(psm3 instanceof Map<?,?> map && !map.get("adoption").equals("original")) {
            selected=map.get("selected");adoption="existing-psm3-"+map.get("adoption");
        } else if(psm6 instanceof Map<?,?> map && !map.get("adoption").equals("original")) {
            selected=map.get("selected");adoption="hypothetical-psm6-"+map.get("adoption");
        }
        report.put("conditionalSelected",selected);report.put("conditionalAdoption",adoption);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("selection.json").toFile(),report);
    }
}
