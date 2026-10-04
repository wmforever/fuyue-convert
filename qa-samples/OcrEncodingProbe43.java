package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Pixel-only audit. No OCR, resizing, renderer, or production changes. */
public final class OcrEncodingProbe43 {
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static int whiteGray(int rgba) {
        int gray=(((rgba>>>16)&255)*299+((rgba>>>8)&255)*587+(rgba&255)*114+500)/1000;
        int alpha=(rgba>>>24)&255;
        return (gray*alpha+255*(255-alpha)+127)/255;
    }
    static Map<String,Object> pixels(BufferedImage image) throws Exception {
        int width=image.getWidth(),height=image.getHeight();
        byte[] rgb=new byte[width*height*3],gray=new byte[width*height];
        int[] row=new int[width];
        for(int y=0;y<height;y++) {
            image.getRGB(0,y,width,1,row,0,width);
            for(int x=0;x<width;x++) {
                int p=y*width+x,v=row[x];
                rgb[3*p]=(byte)(v>>>16);rgb[3*p+1]=(byte)(v>>>8);rgb[3*p+2]=(byte)v;
                gray[p]=(byte)whiteGray(v);
            }
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("type",image.getType());result.put("width",width);result.put("height",height);
        result.put("colorSpaceType",image.getColorModel().getColorSpace().getType());
        result.put("isCSsRGB",image.getColorModel().getColorSpace().isCS_sRGB());
        result.put("hasAlpha",image.getColorModel().hasAlpha());result.put("premultiplied",image.isAlphaPremultiplied());
        result.put("rasterBands",image.getRaster().getNumBands());
        result.put("getRGBHash",hash(rgb));result.put("whiteLuminanceHash",hash(gray));
        result.put("firstPixelARGB",Integer.toUnsignedString(image.getRGB(0,0),16));
        result.put("firstRasterSample",image.getRaster().getSample(0,0,0));
        return result;
    }
    static Map<String,Object> enhanced(BufferedImage source,Path path) throws Exception {
        var image=OcrContrastEnhancer.enhance(source);
        if(image==null)return Map.of("available",false);
        if(image.getWidth()!=source.getWidth()||image.getHeight()!=source.getHeight())throw new AssertionError("resampling");
        var result=new LinkedHashMap<String,Object>(pixels(image));
        result.put("available",true);
        result.put("rawGrayHash",hash((byte[])image.getRaster().getDataElements(0,0,image.getWidth(),image.getHeight(),null)));
        if(!ImageIO.write(image,"png",path.toFile()))throw new AssertionError("no writer");
        result.put("pngSha256",hash(Files.readAllBytes(path)));image.flush();return result;
    }
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var out=Path.of(args[0]);Files.createDirectory(out);
        var results=new ArrayList<Map<String,Object>>();long start=System.nanoTime();
        for(int i=1;i<args.length;i++) {
            var input=Path.of(args[i]);var folder=out.resolve(input.getFileName().toString().replace(".png",""));Files.createDirectory(folder);
            var source=ImageIO.read(input.toFile());if(source==null)throw new AssertionError("decode");
            int width=source.getWidth(),height=source.getHeight();
            var row=new LinkedHashMap<String,Object>();row.put("input",input.toString());row.put("sourceSha256",hash(Files.readAllBytes(input)));
            row.put("decoded",pixels(source));row.put("enhanced",enhanced(source,folder.resolve("enhanced.png")));
            // Definition B: exact enhancer-input luminance after Java getRGB and integer white matte.
            // All fixtures are achromatic; no colored-channel rounding is being equated.
            var equivalent=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            int[] buffer=new int[width];
            for(int y=0;y<height;y++) {
                source.getRGB(0,y,width,1,buffer,0,width);
                for(int x=0;x<width;x++){int g=whiteGray(buffer[x]);buffer[x]=0xff000000|(g<<16)|(g<<8)|g;}
                equivalent.setRGB(0,y,width,1,buffer,0,width);
            }
            var equivalentPath=folder.resolve("java-white-equivalent.png");ImageIO.write(equivalent,"png",equivalentPath.toFile());
            var reread=ImageIO.read(equivalentPath.toFile());row.put("javaEquivalent",pixels(reread));
            row.put("javaEquivalentPngSha256",hash(Files.readAllBytes(equivalentPath)));
            row.put("javaEquivalentEnhanced",enhanced(reread,folder.resolve("java-white-equivalent-enhanced.png")));
            results.add(row);source.flush();equivalent.flush();reread.flush();
        }
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("report.json").toFile(),Map.of("cases",results,"newOCR",0,"javaVersion",System.getProperty("java.runtime.version"),"wallSeconds",(System.nanoTime()-start)/1e9));
        System.out.println("Pixel audit completed: "+results.size()+" inputs, zero OCR");
    }
}
