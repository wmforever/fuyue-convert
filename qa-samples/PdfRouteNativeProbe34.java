package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Parse immutable PDFs and detector inputs; zero OCR and zero Office invocations. */
public class PdfRouteNativeProbe34 {
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();var manifest=mapper.readTree(Path.of(args[0]).toFile());var rows=new ArrayList<Object>();
        for(var item:manifest.get("files")) {
            Path path=Path.of(item.get("path").asText());var sections=new PdfTextSectionOrder();
            var parsed=new PdfLayoutParser().parseForTextSections(path,path.getFileName().toString(),ParseLimits.defaults(),true,sections);
            var page=parsed.pages().get(0);var row=new LinkedHashMap<String,Object>();
            row.put("case",item.get("id").asText());row.put("pdfSha256",sha(Files.readAllBytes(path)));row.put("physicalBox",page.physicalBox());
            row.put("nativeBlocks",page.textBlocks());row.put("nativeOnlyHypotheticalText",sections.text(List.of(new PageLayoutAnalyzer().analyze(page))));
            row.put("nativeCharacters",page.textBlocks().stream().mapToInt(b->(int)b.text().codePoints().filter(c->!Character.isWhitespace(c)).count()).sum());
            row.put("requiredImages",ScannedContentDetector.imagesRequiringOcr(page.textBlocks(),page.images(),page.physicalBox()).size());row.put("parserWarnings",page.warnings());
            var images=new ArrayList<Object>();
            for(var image:page.images()) {
                var raster=ImageIO.read(new ByteArrayInputStream(image.data()));var digest=MessageDigest.getInstance("SHA-256");var line=ByteBuffer.allocate(raster.getWidth()*4);
                int darkPixels=0;for(int y=0;y<raster.getHeight();y++) {line.clear();for(int x=0;x<raster.getWidth();x++) {
                    int rgb=raster.getRGB(x,y);line.putInt(rgb);if(((rgb>>>16)&255)<128 && ((rgb>>>8)&255)<128 && (rgb&255)<128)darkPixels++;
                }digest.update(line.array());}
                int chars=0;boolean[] bands=new boolean[4];double first=Double.POSITIVE_INFINITY,last=Double.NEGATIVE_INFINITY;
                for(var text:page.textBlocks()) {var box=text.box();if(image.box().intersectionArea(box)/Math.max(.01,box.width()*box.height())<.5 && !image.box().contains(box.center(),.5))continue;
                    chars+=(int)text.text().codePoints().filter(c->!Character.isWhitespace(c)).count();double y=box.center().y();first=Math.min(first,y);last=Math.max(last,y);
                    bands[(int)(Math.max(0,Math.min(.999999,(y-image.box().y())/image.box().height()))*4)]=true;
                }
                int occupied=0;for(boolean used:bands)if(used)occupied++;
                images.add(Map.of("box",image.box(),"pixelSha256",HexFormat.of().formatHex(digest.digest()),"darkPixels",darkPixels,"width",raster.getWidth(),"height",raster.getHeight(),
                    "nativeCharactersWithinImage",chars,"occupiedNativeVerticalBands",occupied,"nativeVerticalSpanRatio",Double.isFinite(first)?(last-first)/image.box().height():0));raster.flush();
            }
            row.put("images",images);rows.add(row);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),Map.of("rows",rows,"ocrInvocations",0,"officeInvocations",0));
        System.out.println("parsed="+rows.size()+" OCR=0 Office=0");
    }
    private static String sha(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
}
