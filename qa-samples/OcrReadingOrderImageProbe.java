package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;

/** Parse actual native TSV at the frozen300DPI source geometry and inspect TXT ordering. */
public class OcrReadingOrderImageProbe {
    public static void main(String[] args) throws Exception {
        Path imagePath=Path.of(args[0]), tsv=Path.of(args[1]), output=Path.of(args[2]), runtime=Path.of(args[3]);
        var image=ImageIO.read(imagePath.toFile());
        Rect page=new Rect(0,0,image.getWidth()*25.4/300,image.getHeight()*25.4/300);
        var converter=new TesseractOcrConverter(DocumentFormat.PNG,runtime.resolve("bin/tesseract"),"chi_sim+eng",Duration.ofSeconds(120));
        Class<?> dimensions=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");
        var constructor=dimensions.getDeclaredConstructor(int.class,int.class);constructor.setAccessible(true);
        var parse=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dimensions,ParseLimits.class);
        parse.setAccessible(true);
        var original=(TesseractOcrConverter.RecognitionResult)parse.invoke(converter,tsv,1,page,
            constructor.newInstance(image.getWidth(),image.getHeight()),ParseLimits.defaults());
        var snapshot=List.copyOf(original.blocks());
        var result=OcrReadingOrder.arrange(original.blocks(),page.width(),System.nanoTime()+1_000_000_000);
        if (!snapshot.equals(original.blocks())) throw new AssertionError("Original blocks changed");
        for (int i=0;i<snapshot.size();i++)
            if (snapshot.get(i)!=original.blocks().get(i)) throw new AssertionError("Original objects changed");
        var report=new LinkedHashMap<String,Object>();
        report.put("original",original);report.put("arranged",result);report.put("sourceObjectsExact",true);
        report.put("sourceWidth",image.getWidth());report.put("sourceHeight",image.getHeight());report.put("sourceDpi",300);
        report.put("originalCoordinates",page);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        image.flush();
    }
}
