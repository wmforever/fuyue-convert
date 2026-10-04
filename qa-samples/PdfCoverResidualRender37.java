package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Diagnostic paint projection: preserve scan/covers, omit native glyph paint; zero OCR/Office/HTTP. */
public class PdfCoverResidualRender37 {
    public static void main(String[] args)throws Exception {
        var mapper=new ObjectMapper();var trace=mapper.readTree(Path.of(args[0]).toFile());Path out=Path.of(args[1]);Files.createDirectory(out);var rows=new ArrayList<Object>();
        for(int index:new int[]{0,2}) {
            var row=trace.get("rows").get(index);if(row.get("unsupported").asBoolean())throw new IllegalStateException("Unsupported text state cannot be projected");
            Path source=Path.of(row.get("sourcePath").asText());if(!sha(Files.readAllBytes(source)).equals(row.get("pdfSha256").asText()))throw new IllegalStateException("Frozen PDF changed");
            try(var pdf=Loader.loadPDF(source.toFile())) {
                var page=pdf.getPage(0);var kept=new ArrayList<Object>();var operands=new ArrayList<Object>();int removed=0;
                for(var token:new PDFStreamParser(page).parse()) {
                    if(token instanceof Operator operator) {
                        if(Set.of("Tj","TJ","'","\"").contains(operator.getName()))removed++;
                        else {kept.addAll(operands);kept.add(token);}operands.clear();
                    } else operands.add(token);
                }
                kept.addAll(operands);var stream=new PDStream(pdf);
                try(var output=stream.createOutputStream()){new ContentStreamWriter(output).writeTokens(kept);}page.setContents(stream);
                String id=row.get("id").asText();Path projection=out.resolve(id+"-scan-covers-only.pdf");pdf.save(projection.toFile());
                var pixels=new PDFRenderer(pdf).renderImageWithDPI(0,300,ImageType.RGB);Path raster=out.resolve(id+"-scan-covers-only.png");ImageIO.write(pixels,"png",raster.toFile());
                var values=new ArrayList<Object>();
                for(var word:row.get("words")) {
                    var box=word.get("sourceBox");int left=Math.max(0,(int)Math.floor(box.get("x").asDouble()*300/25.4)),top=Math.max(0,(int)Math.floor(box.get("y").asDouble()*300/25.4));
                    int right=Math.min(pixels.getWidth(),(int)Math.ceil((box.get("x").asDouble()+box.get("width").asDouble())*300/25.4));
                    int bottom=Math.min(pixels.getHeight(),(int)Math.ceil((box.get("y").asDouble()+box.get("height").asDouble())*300/25.4));int nonWhite=0,dark=0;
                    for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){int rgb=pixels.getRGB(x,y)&0xffffff;if(rgb!=0xffffff)nonWhite++;if(((rgb>>>16)&255)<128&&((rgb>>>8)&255)<128&&(rgb&255)<128)dark++;}
                    String name=word.get("text").asText();var value=new LinkedHashMap<String,Object>();value.put("text",name);value.put("trigger",word.get("trigger"));value.put("remainingRenderedNonWhite",nonWhite);value.put("remainingRenderedDark",dark);value.put("cropPixels",List.of(left,top,right,bottom));
                    if(!word.get("trigger").isNull()) {Path crop=out.resolve(id+"-word-"+values.size()+".png");var image=pixels.getSubimage(left,top,right-left,bottom-top);ImageIO.write(image,"png",crop.toFile());value.put("crop",crop.getFileName().toString());value.put("cropSha256",sha(Files.readAllBytes(crop)));}
                    values.add(value);
                }
                rows.add(Map.of("id",id,"sourcePdfSha256",row.get("pdfSha256").asText(),"removedGlyphPaintOperators",removed,"projectionPdf",projection.getFileName().toString(),"projectionPdfSha256",sha(Files.readAllBytes(projection)),"projectionPng",raster.getFileName().toString(),"projectionPngSha256",sha(Files.readAllBytes(raster)),"words",values));pixels.flush();
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("projection.json").toFile(),Map.of("rows",rows,"ocrInvocations",0,"officeInvocations",0,"httpRequests",0,"diagnosticProjectionNotActualFinalPage",true));
        System.out.println("diagnostic2 projections;OCR=0 Office=0 HTTP=0");
    }
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
