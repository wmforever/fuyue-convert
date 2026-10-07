package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
/** Diagnose unchanged selection gates from existing native TSVs; no OCR or relaxed selector. */
public final class OcrShadowCompletenessProbe {
 public static void main(String[] args)throws Exception {
  var image=ImageIO.read(Path.of(args[0]).toFile());var page=new Rect(0,0,image.getWidth()*25.4/300,image.getHeight()*25.4/300);
  var converter=new TesseractOcrConverter(DocumentFormat.PNG,Path.of(args[3]).resolve("bin/tesseract"),"chi_sim+eng",Duration.ofSeconds(120));
  var dimensions=Class.forName("com.fuyue.formatconverter.task.TesseractOcrConverter$ImageDimensions");var ctor=dimensions.getDeclaredConstructor(int.class,int.class);ctor.setAccessible(true);
  var parse=TesseractOcrConverter.class.getDeclaredMethod("parseTsv",Path.class,int.class,Rect.class,dimensions,ParseLimits.class);parse.setAccessible(true);
  var dim=ctor.newInstance(image.getWidth(),image.getHeight());
  var original=(TesseractOcrConverter.RecognitionResult)parse.invoke(converter,Path.of(args[1]),1,page,dim,ParseLimits.defaults());var candidate=(TesseractOcrConverter.RecognitionResult)parse.invoke(converter,Path.of(args[2]),1,page,dim,ParseLimits.defaults());
  var snapshot=List.copyOf(original.blocks());var partial=OcrPartialRecovery.select(original,candidate,page,.35,.05,true,0,System.nanoTime()+2_000_000_000L);
  var stable=TesseractOcrConverter.class.getDeclaredMethod("originalGeometryStable",TesseractOcrConverter.RecognitionResult.class,java.awt.image.BufferedImage.class,Rect.class,long.class);stable.setAccessible(true);
  double required=Math.max(.35,original.confidence()+.05);var rows=new ArrayList<Map<String,Object>>();
  for(var block:candidate.blocks()){
   var overlaps=original.blocks().stream().filter(b->b.box().intersectionArea(block.box())>0).map(TextBlock::id).toList();double avg=block.ocrWords().stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElse(0);
   rows.add(Map.of("text",block.text(),"box",block.box(),"overlappingOriginalIds",overlaps,"newRow",overlaps.isEmpty(),"averageConfidence",avg,"minimumWordConfidence",block.ocrWords().stream().mapToDouble(TextBlock.OcrWord::confidence).min().orElse(0),"requiredConfidence",required,"unchangedNewRowGainPass",avg>=required));
  }
  for(int i=0;i<snapshot.size();i++)if(snapshot.get(i)!=original.blocks().get(i))throw new AssertionError("original mutated");
  var report=new LinkedHashMap<String,Object>();report.put("original",original);report.put("candidate",candidate);report.put("requiredConfidence",required);report.put("candidateGain",candidate.confidence()-original.confidence());report.put("globalCandidateGainPass",candidate.confidence()>=required);report.put("fullAccepted",TesseractOcrConverter.preferEnhanced(original,candidate,.35));report.put("partialAcceptedBeforeGeometry",partial.partialRecovery());report.put("partialReturnedOriginalObject",partial==original);report.put("originalGeometryStable",stable.invoke(null,original,image,page,System.nanoTime()+2_000_000_000L));report.put("uncoveredShadedInk",OcrCoverageProbe.hasUncoveredShadedInk(image,original.blocks(),page,System.nanoTime()+2_000_000_000L));report.put("candidateRows",rows);report.put("sourceObjectsExact",true);
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[4]).toFile(),report);image.flush();
 }
}
