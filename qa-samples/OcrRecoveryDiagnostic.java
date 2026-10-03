package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Diagnostic alternatives preserve pixels/PSM and never consult source truth. */
public class OcrRecoveryDiagnostic {
    public static void main(String[] args) throws Exception {
        Path image = Path.of(args[0]), work = Path.of(args[1]);Files.createDirectories(work);
        var capability = TesseractOcrConverter.detectConfigured();
        if (!capability.available() || !capability.settings().bundled()) throw new IllegalStateException("Pinned runtime required");
        var converter = new TesseractOcrConverter(DocumentFormat.PNG, capability.settings());
        var dimensions = TesseractOcrConverter.class.getDeclaredMethod("dimensions",Path.class);dimensions.setAccessible(true);
        var recognize = Arrays.stream(TesseractOcrConverter.class.getDeclaredMethods()).filter(m->m.getName().equals("recognizeOnce")).findFirst().orElseThrow();recognize.setAccessible(true);
        var pixels=ImageIO.read(image.toFile());Rect physical=new Rect(0,0,pixels.getWidth(),pixels.getHeight());
        var original=(TesseractOcrConverter.RecognitionResult)recognize.invoke(converter,image,work,1,physical,
                dimensions.invoke(converter,image),ParseLimits.defaults(),"original",Duration.ofSeconds(120));
        Map<String,Object> result=new LinkedHashMap<>();result.put("original",original);
        long probeStart = System.nanoTime();
        result.put("coverageRecoveryEligible", OcrCoverageProbe.hasUncoveredShadedInk(pixels, original.blocks(), physical,
                probeStart + Duration.ofSeconds(1).toNanos()));
        result.put("coverageProbeMillis", (System.nanoTime() - probeStart) / 1_000_000d);
        result.put("originalReadingOrder", OcrReadingOrder.arrange(original.blocks(), physical.width(),
                System.nanoTime() + Duration.ofSeconds(1).toNanos()));
        var enhanced=OcrContrastEnhancer.enhance(pixels);
        if(enhanced!=null){
            Path path=work.resolve("enhanced.png");ImageIO.write(enhanced,"png",path.toFile());enhanced.flush();
            var candidate=(TesseractOcrConverter.RecognitionResult)recognize.invoke(converter,path,work,1,physical,
                    dimensions.invoke(converter,path),ParseLimits.defaults(),"enhanced",Duration.ofSeconds(120));
            result.put("enhanced",candidate);result.put("preferEnhanced",TesseractOcrConverter.preferEnhanced(original,candidate,capability.settings().minimumConfidence()));
        }
        pixels.flush();new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(work.resolve("diagnostic.json").toFile(),result);
    }
}
