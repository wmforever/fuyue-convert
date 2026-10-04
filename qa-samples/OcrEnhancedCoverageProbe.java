package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import java.nio.file.*;
import java.time.Duration;
import javax.imageio.ImageIO;
import java.util.*;

/** Controlled process contract; engine confidence is injected, not measured OCR quality. */
public class OcrEnhancedCoverageProbe {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),engine=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
        var settings=new TesseractOcrConverter.Settings(engine,"eng","controlled",Duration.ofSeconds(120),1,
                .35,.75,25_000_000,out.resolve("locks"));
        var converter=new TesseractOcrConverter(DocumentFormat.PNG,settings);
        var pixels=ImageIO.read(source.toFile());Rect page=new Rect(0,0,203.2,127);
        long start=System.nanoTime();var result=converter.recognizeLayoutResult(source,out.resolve("work"),1,page,ParseLimits.defaults());
        boolean coverage=OcrCoverageProbe.hasUncoveredShadedInk(pixels,result.blocks(),page,System.nanoTime()+1_000_000_000L);
        var record=new LinkedHashMap<String,Object>();record.put("result",result);record.put("selectedStillUncovered",coverage);
        record.put("warnings",converter.warningsFor(result,1,"第1页"));record.put("seconds",(System.nanoTime()-start)/1e9);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("result.json").toFile(),record);
        System.out.println(source.getFileName()+" enhanced="+result.imageEnhanced()+" probe="+coverage+" flag="+result.possibleTextOmission());
        pixels.flush();
    }
}
