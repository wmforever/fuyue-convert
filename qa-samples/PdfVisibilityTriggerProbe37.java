package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import javax.imageio.ImageIO;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Trace actual visibility proof and source-image OCR once per unique frozen image. */
public class PdfVisibilityTriggerProbe37 {
    private static final double MM=25.4/72;
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectory(out);var mapper=new ObjectMapper();var limits=ParseLimits.defaults();
        var capability=TesseractOcrConverter.detectConfigured();if(!capability.available()||!capability.settings().bundled())throw new IllegalStateException("Bundled required");
        var ocr=new TesseractOcrConverter(DocumentFormat.PNG,capability.settings());var cache=new HashMap<String,TesseractOcrConverter.RecognitionResult>();var rows=new ArrayList<Object>();
        var edgeField=PdfOcrSupport.class.getDeclaredField("EMBEDDED_IMAGE_OCR_MAX_EDGE");edgeField.setAccessible(true);int maxEdge=edgeField.getInt(null);
        for(int i=1;i<args.length;i++) {
            Path source=Path.of(args[i]);String id=i+"-"+source.getFileName().toString().replace("-result.pdf","");Path work=Files.createDirectory(out.resolve(id));
            var parsed=new PdfLayoutParser().parseForTextExtractionOcr(source,source.getFileName().toString(),limits);var page=parsed.pages().get(0);if(page.images().size()!=1)throw new IllegalStateException("One image required");
            var image=page.images().get(0);var pixels=ImageIO.read(new ByteArrayInputStream(image.data()));Path raster=work.resolve("source.png");ImageIO.write(pixels,"png",raster.toFile());
            Path prepared=OcrImageNormalizer.downscaleForOcr(raster,work.resolve("prepared.png"),maxEdge);String key=sha(Files.readAllBytes(prepared))+image.box();
            var recognition=cache.get(key);boolean cached=recognition!=null;
            if(!cached){recognition=ocr.recognizeLayoutResult(prepared,work.resolve("ocr"),1,image.box(),limits);cache.put(key,recognition);}
            var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("sourcePath",source.toString());row.put("pdfSha256",sha(Files.readAllBytes(source)));row.put("embeddedImageMaxEdge",maxEdge);row.put("imageBox",image.box());row.put("nativeBlocks",page.textBlocks());row.put("recognition",recognition);row.put("recognitionCached",cached);row.put("sourcePngSha256",sha(Files.readAllBytes(raster)));
            try(var pdf=Loader.loadPDF(source.toFile())) {
                var visibility=PdfOcrVisibility.inspect(pdf.getPage(0),page.images(),limits.maxEntries());if(visibility==null)throw new IllegalStateException("No visibility proof");
                Area opaque=(Area)field(visibility,"opaque"),uncertain=(Area)field(visibility,"uncertain"),clip=(Area)field(visibility,"imageClip");boolean unsupported=(boolean)field(visibility,"unsupported");
                row.put("opaqueBounds",rectangle(opaque.getBounds2D()));row.put("uncertainBounds",rectangle(uncertain.getBounds2D()));row.put("unsupported",unsupported);row.put("operators",field(visibility,"operators"));row.put("covers",field(visibility,"covers"));
                var words=new ArrayList<Object>();int examined=0;String firstReason=null;
                for(var block:recognition.blocks()) {
                    int hidden=0;boolean failed=false;
                    for(var word:block.ocrWords()) {
                        Rect box=word.box();Rectangle2D pdfBox=new Rectangle2D.Double(box.x()/MM,pdf.getPage(0).getCropBox().getHeight()-box.bottom()/MM,box.width()/MM,box.height()/MM);
                        boolean inside=clip.contains(pdfBox),touchOpaque=opaque.intersects(pdfBox),touchUncertain=uncertain.intersects(pdfBox),covered=opaque.contains(pdfBox);
                        String reason=!inside?"imageClipDoesNotContainWord":(touchOpaque||touchUncertain)?unsupported?"unsupportedPdfState":touchUncertain?"uncertainPaintIntersectsWord":!covered?"opaqueDoesNotContainWholeWord":null:null;
                        if(reason==null&&touchOpaque)hidden++;if(reason!=null)failed=true;if(firstReason==null&&reason!=null)firstReason=reason;
                        int left=Math.max(0,(int)Math.floor((box.x()-image.box().x())*pixels.getWidth()/image.box().width()));int right=Math.min(pixels.getWidth(),(int)Math.ceil((box.right()-image.box().x())*pixels.getWidth()/image.box().width()));
                        int top=Math.max(0,(int)Math.floor((box.y()-image.box().y())*pixels.getHeight()/image.box().height()));int bottom=Math.min(pixels.getHeight(),(int)Math.ceil((box.bottom()-image.box().y())*pixels.getHeight()/image.box().height()));
                        int whiteOutside=0,inkOutside=0;Rectangle2D outsideBounds=null;
                        if(examined+(long)(right-left)*(bottom-top)>250000)throw new IllegalStateException("Diagnostic pixel budget exceeded");
                        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++) {
                            examined++;double wx=image.box().x()+x*image.box().width()/pixels.getWidth(),wy=image.box().y()+y*image.box().height()/pixels.getHeight();
                            Rectangle2D cell=new Rectangle2D.Double(wx/MM,pdf.getPage(0).getCropBox().getHeight()-(wy+image.box().height()/pixels.getHeight())/MM,image.box().width()/pixels.getWidth()/MM,image.box().height()/pixels.getHeight()/MM);
                            if(!opaque.contains(cell)) {
                                if((pixels.getRGB(x,y)&0xffffff)==0xffffff)whiteOutside++;else {inkOutside++;Rectangle2D point=new Rectangle2D.Double(x,y,1,1);if(outsideBounds==null)outsideBounds=point;else outsideBounds=outsideBounds.createUnion(point);}
                            }
                        }
                        var w=new LinkedHashMap<String,Object>();w.put("text",word.text());w.put("sourceBox",box);w.put("pdfBox",rectangle(pdfBox));w.put("insideImageClip",inside);w.put("touchOpaque",touchOpaque);w.put("touchUncertain",touchUncertain);w.put("opaqueContainsWholeWord",covered);w.put("trigger",reason);w.put("sourceWhitePixelsOutsideOpaque",whiteOutside);w.put("sourceInkPixelsNotFullyCovered",inkOutside);w.put("sourceInkOutsidePixelBounds",outsideBounds==null?null:rectangle(outsideBounds));words.add(w);
                    }
                    if(!failed&&hidden!=0&&hidden!=block.ocrWords().size()&&firstReason==null)firstReason="partlyHiddenLine";
                }
                row.put("words",words);row.put("diagnosticPixelsExamined",examined);row.put("firstTrigger",firstReason);
                try{var filtered=visibility.filter(recognition.blocks());row.put("filterSuccess",true);row.put("filtered",filtered);}catch(ConversionFailureException e){row.put("filterSuccess",false);row.put("errorCode",e.code());}
            }
            rows.add(row);pixels.flush();
            mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("trace.json").toFile(),Map.of("rows",rows,"uniqueSourceRecognitions",cache.size(),"officeInvocations",0,"httpRequests",0,"status","running"));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("trace.json").toFile(),Map.of("rows",rows,"uniqueSourceRecognitions",cache.size(),"officeInvocations",0,"httpRequests",0,"status","completed"));
        System.out.println("PDFs="+rows.size()+" uniqueSourceOCR="+cache.size()+" HTTP=0 Office=0");
    }
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static List<Double> rectangle(Rectangle2D r){return List.of(r.getX(),r.getY(),r.getWidth(),r.getHeight());}
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
