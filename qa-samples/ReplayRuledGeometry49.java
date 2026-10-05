package com.fuyue.formatconverter.task;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;import java.util.*;import javax.imageio.ImageIO;
/** Replays captured production TSVs through the final selector; never launches OCR. */
public class ReplayRuledGeometry49 {
 public static void main(String[] a)throws Exception {
  var json=new ObjectMapper();var plan=json.readTree(Path.of(a[0]).toFile());var output=new ArrayList<Object>();
  var dims=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");var dc=dims.getDeclaredConstructor(int.class,int.class);dc.setAccessible(true);
  var parse=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dims,ParseLimits.class);parse.setAccessible(true);
  var engine=new TesseractOcrConverter(DocumentFormat.PNG,new TesseractOcrConverter.Settings(Path.of("/nonexistent-no-ocr"),"chi_sim+eng","captured-only"));
  for(var c:plan) {
   var page=new PdfLayoutParser().parseForEditableOcr(Path.of(c.get("pdf").asText()),"frozen",ParseLimits.defaults()).pages().get(0).physicalBox();
   var pixels=ImageIO.read(Path.of(c.get("image").asText()).toFile());var grid=OcrRuledGrid.detect(pixels,page,Long.MAX_VALUE);
   var old=(TesseractOcrConverter.RecognitionResult)parse.invoke(engine,Path.of(c.get("originalTsv").asText()),1,page,dc.newInstance(pixels.getWidth(),pixels.getHeight()),ParseLimits.defaults());
   var candidates=new ArrayList<TesseractOcrConverter.RecognitionResult>();
   for(int i=0;i<grid.cells().size();i++){var cell=grid.cells().get(i);candidates.add((TesseractOcrConverter.RecognitionResult)parse.invoke(engine,Path.of(c.get("cells").get(i).asText()),1,cell.box(),dc.newInstance(cell.width(),cell.height()),ParseLimits.defaults()));}
   var selected=OcrRuledGrid.select(grid,old,candidates,.35,Long.MAX_VALUE);if(!selected.ruledGridRecovery())throw new AssertionError("candidate rejected");
   var original=old.blocks().stream().flatMap(b->b.ocrWords().stream()).toList();var actual=selected.blocks().stream().flatMap(b->b.ocrWords().stream()).toList();
   var numbers=original.stream().filter(w->w.text().codePoints().anyMatch(Character::isDigit)).toList();
   if(!numbers.stream().allMatch(w->actual.stream().anyMatch(v->v==w)))throw new AssertionError("original numeric word/coordinate not retained");
   output.add(Map.of("case",c.get("case").asText(),"originalNumericWords",numbers,"allOriginalNumericWordsRetainedExactly",true,"extraOcrInvocations",0,"selectedWords",actual,"originalConfidence",old.confidence(),"selectedConfidence",selected.confidence()));pixels.flush();
  }
  json.writerWithDefaultPrettyPrinter().writeValue(Path.of(a[1]).toFile(),output);
 }
}
