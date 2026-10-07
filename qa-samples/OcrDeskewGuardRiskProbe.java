package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;

/** Inspect actual native source TSV and global projection without additional OCR passes. */
public final class OcrDeskewGuardRiskProbe {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),tsv=Path.of(args[1]),output=Path.of(args[2]),runtime=Path.of(args[3]);
        var image=ImageIO.read(source.toFile());
        var physical=new Rect(0,0,image.getWidth()*25.4/300,image.getHeight()*25.4/300);
        var converter=new TesseractOcrConverter(DocumentFormat.PNG,runtime.resolve("bin/tesseract"),"chi_sim+eng",Duration.ofSeconds(120));
        var dimensions=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");
        var ctor=dimensions.getDeclaredConstructor(int.class,int.class);ctor.setAccessible(true);
        var parse=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dimensions,ParseLimits.class);parse.setAccessible(true);
        var original=(TesseractOcrConverter.RecognitionResult)parse.invoke(converter,tsv,1,physical,ctor.newInstance(image.getWidth(),image.getHeight()),ParseLimits.defaults());
        var snapshot=List.copyOf(original.blocks());
        var arranged=OcrReadingOrder.arrange(original.blocks(),physical.width(),System.nanoTime()+Duration.ofSeconds(2).toNanos());
        var strict=OcrFragmentedColumns.arrange(original.blocks(),physical.width(),System.nanoTime()+Duration.ofSeconds(2).toNanos());
        for(int i=0;i<snapshot.size();i++)if(snapshot.get(i)!=original.blocks().get(i))throw new AssertionError("source mutated");
        var report=new LinkedHashMap<String,Object>();report.put("original",original);report.put("arranged",arranged);report.put("strict",strict);
        report.put("originalGuardQualifies",arranged.multipleColumns()&&arranged.adjusted());
        report.put("strictValidationSucceeded",strict.lines().size()<original.blocks().size());
        report.put("globalDetectedDegrees",OcrDeskew.detect(image,System.nanoTime()+Duration.ofSeconds(3).toNanos()));
        report.put("sourceObjectsExact",true);report.put("originalCoordinateBox",physical);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);image.flush();
    }
}
