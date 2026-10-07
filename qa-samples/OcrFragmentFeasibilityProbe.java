package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.nio.file.*;
import java.util.*;
/** Actual Java geometry/flag checks; hand-constructed blocks are not native OCR evidence. */
public final class OcrFragmentFeasibilityProbe {
 static List<TextBlock> blocks(double angle,double offset,boolean numeric) {
  var out=new ArrayList<TextBlock>();String[] labels={"Alpha","Bravo","Cedar"},tokens={"refAB-00562","dated2028-05-17","USD-043.70"};
  var rotation=AffineTransform.getRotateInstance(Math.toRadians(angle),500,250);
  for(int col=0;col<3;col++)for(int row=0;row<3;row++)for(int part=0;part<3;part++){
   double x=40+col*330+part*70,y=100+row*100+col*offset/2;double l=Double.POSITIVE_INFINITY,t=l,r=-l,b=-l;
   for(double xx:new double[]{x,x+60})for(double yy:new double[]{y,y+20}){var p=rotation.transform(new Point2D.Double(xx,yy),null);l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}
   var box=new Rect(l,t,r-l,b-t);String text=part==0?labels[col]:part==1?"keeps":numeric?"12":tokens[row];
   out.add(new TextBlock("s"+col+"r"+row+"p"+part,1,box,text,box.bottom(),null,0,0,0,List.of(),Transform2D.IDENTITY,List.of(new TextBlock.OcrWord(box,text,.92))));
  }return out;
 }
 static Map<String,Object> check(String label,List<TextBlock> blocks,long budget,boolean validated,boolean adjusted){
  var snapshot=List.copyOf(blocks);long start=System.nanoTime();var result=OcrReadingOrder.arrange(blocks,1000,start+budget);double seconds=(System.nanoTime()-start)/1e9;
  if(result.fragmentedColumnsValidated()!=validated||result.adjusted()!=adjusted)throw new AssertionError(label+" unexpected flags "+result);
  for(int i=0;i<snapshot.size();i++)if(snapshot.get(i)!=blocks.get(i))throw new AssertionError("source mutation");
  var record=new LinkedHashMap<String,Object>();record.put("label",label);record.put("result",result);record.put("blockCount",blocks.size());record.put("wordCount",blocks.stream().mapToInt(b->b.ocrWords().size()).sum());record.put("seconds",seconds);record.put("sourceObjectsExact",true);record.put("deadlineBudgetNanos",budget);return record;
 }
 public static void main(String[] args)throws Exception {
  var records=new ArrayList<Map<String,Object>>();
  for(double angle:new double[]{0,2,4})records.add(check("model-angle"+angle,blocks(angle,0,false),2_000_000_000L,angle<4,false));
  for(double offset:new double[]{49.9,50,50.1})records.add(check("coverage-offset"+offset,blocks(0,offset,false),2_000_000_000L,offset<=50,false));
  records.add(check("numeric-fallback",blocks(0,0,true),2_000_000_000L,false,false));
  records.add(check("expired",blocks(0,0,false),-1,false,false));
  var dense=Collections.nCopies(501,blocks(0,0,false).get(0));records.add(check("501-block-budget",dense,2_000_000_000L,false,false));
  var b=blocks(0,0,false).get(0);var words=Collections.nCopies(5001,b.ocrWords().get(0));var many=new TextBlock(b.id(),1,b.box(),b.text(),b.baselineY(),null,0,0,0,List.of(),Transform2D.IDENTITY,words);records.add(check("5001-word-budget",Collections.nCopies(3,many),2_000_000_000L,false,false));
  var result=new LinkedHashMap<String,Object>();result.put("scope","Model-level actual Java only; does not establish native quality");result.put("cases",records);result.put("analytic",Map.of("modelRowSpan",200,"modelFragmentCenterSpan",140,"modelInkHeight",20,"edgeInequality","abs(sin(theta))*rowSpan <= medianRotatedHeight/2","rowGroupingInequality","abs(sin(theta))*fragmentCenterSpan <= medianRotatedHeight*0.35","originalNativeRowSpan",870,"chosenNativeAngles",List.of(-.35,.35,-.65,.65)));
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),result);System.out.println("10 actual model/boundary/resource checks passed;2-degree geometry feasible,4-degree rejected");
 }
}
