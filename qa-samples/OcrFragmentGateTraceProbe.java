package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
/** Match traced pure arrangement to production and record every evaluated rejection gate. */
public final class OcrFragmentGateTraceProbe {
 public static void main(String[] args)throws Exception {
  var image=ImageIO.read(Path.of(args[0]).toFile());var page=new Rect(0,0,image.getWidth()*25.4/300,image.getHeight()*25.4/300);
  var converter=new TesseractOcrConverter(DocumentFormat.PNG,Path.of(args[3]).resolve("bin/tesseract"),"chi_sim+eng",Duration.ofSeconds(120));
  var dimensions=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");var ctor=dimensions.getDeclaredConstructor(int.class,int.class);ctor.setAccessible(true);
  var parse=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dimensions,ParseLimits.class);parse.setAccessible(true);
  var original=(TesseractOcrConverter.RecognitionResult)parse.invoke(converter,Path.of(args[1]),1,page,ctor.newInstance(image.getWidth(),image.getHeight()),ParseLimits.defaults());
  var production=OcrReadingOrder.arrange(original.blocks(),page.width(),System.nanoTime()+2_000_000_000);OcrGateTrace.reset();
  var trace=OcrReadingOrderTrace.arrange(original.blocks(),page.width(),System.nanoTime()+2_000_000_000);
  if(!production.lines().equals(trace.lines())||production.adjusted()!=trace.adjusted()||production.multipleColumns()!=trace.multipleColumns()||production.fragmentedColumnsValidated()!=trace.fragmentedColumnsValidated())throw new AssertionError("instrumentation differs from production");
  var checks=List.copyOf(OcrGateTrace.checks);OcrGateTrace.reset();var strict=OcrFragmentedColumnsTrace.arrange(original.blocks(),page.width(),System.nanoTime()+2_000_000_000);
  var result=new LinkedHashMap<String,Object>();result.put("production",production);result.put("entryTrace",checks);result.put("directStrictTrace",List.copyOf(OcrGateTrace.checks));result.put("directStrictResult",strict);result.put("traceMatchesProduction",true);result.put("original",original);result.put("detectedDegrees",OcrDeskew.detect(image,System.nanoTime()+3_000_000_000L));
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),result);image.flush();
 }
}
