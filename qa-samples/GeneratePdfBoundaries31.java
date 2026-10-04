import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

/** Finite source-explicit whitespace controls; original synthetic strings only. */
public class GeneratePdfBoundaries31 {
    static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static void text(PDPageContentStream c,PDFont f,float size,float x,float y,String s)throws Exception {
        c.beginText();c.setFont(f,size);c.newLineAtOffset(x,y);c.showText(s);c.endText();
    }
    static void pair(PDPageContentStream c,PDFont f,float y,String first,String second,int spaces)throws Exception {
        float size=18,x=40; text(c,f,size,x,y,first+" ".repeat(spaces));
        // The next glyph starts just before the encoded space, reproducing
        // the frozen Office PDF's overlapping advance without OCR tuning.
        float next=x+f.getStringWidth(first)/1000*size-1.5f;
        text(c,f,size,next,y,second);
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectory(out);Path fonts=Path.of(args[1]);
        var sources=new TreeMap<String,String>();var actions=new ArrayList<Object>();var cases=new ArrayList<Object>();
        for(String name:List.of("latin-year","literal-id","numeric-spaces","surface","double-space","mixed-cjk","font-color-id","wide-gap-id")) {
            Path p=out.resolve(name+".pdf");String expected;
            try(var doc=new PDDocument()) {
                var page=new PDPage(PDRectangle.A4);doc.addPage(page);
                var latin=PDType0Font.load(doc,fonts.resolve("LiberationSans-Regular.ttf").toFile());
                try(var c=new PDPageContentStream(doc,page)) {
                    switch(name) {
                        case "latin-year" -> {pair(c,latin,720,"AUDIT","2071",1);expected="AUDIT 2071\n";}
                        case "literal-id" -> {pair(c,latin,720,"CODE","00793",0);expected="CODE00793\n";}
                        case "numeric-spaces" -> {pair(c,latin,720,"12","34",1);text(c,latin,18,40,670,"12.30");expected="12 34\n12.30\n";}
                        case "surface" -> {text(c,latin,18,40,720,"Amount -054.80 +0.47");text(c,latin,18,40,670,"Date 2071-11-23 ID00793");expected="Amount -054.80 +0.47\nDate 2071-11-23 ID00793\n";}
                        case "double-space" -> {pair(c,latin,720,"AUDIT","2074",2);expected="AUDIT  2074\n";}
                        case "mixed-cjk" -> {
                            var cjk=PDType0Font.load(doc,fonts.resolve("DroidSansFallback.ttf").toFile());
                            text(c,cjk,18,40,720,"审核");float x=40+cjk.getStringWidth("审核")/1000*18;
                            text(c,latin,18,x,720," REVIEW 2073");
                            text(c,cjk,18,40,670,"编号，");x=40+cjk.getStringWidth("编号，")/1000*18;
                            text(c,latin,18,x,670,"ID00793");expected="审核 REVIEW 2073\n编号，ID00793\n";
                        }
                        case "font-color-id" -> {c.setNonStrokingColor(java.awt.Color.RED);text(c,latin,18,40,720,"ID");c.setNonStrokingColor(java.awt.Color.BLACK);text(c,latin,18,40+latin.getStringWidth("ID")/1000*18,720,"00864");expected="ID00864\n";}
                        default -> {text(c,latin,18,40,720,"CODE");text(c,latin,18,120,720,"00796");expected="CODE00796\n";}
                    }
                }
                doc.save(p.toFile());
            }
            sources.put(p.getFileName().toString(),sha(p));actions.add(Map.of("id",name+"-text","input",p.getFileName().toString(),"target","txt"));
            cases.add(Map.of("id",name,"expected",expected,"input",p.getFileName().toString()));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("expected.json").toFile(),Map.of("sources",sources,"actions",actions,"cases",cases,"scope","Synthetic explicit native spaces and intentional no-space numeric surfaces; no arbitrary gap tuning"));
    }
}
