import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.fontbox.ttf.TrueTypeFont;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Source font metadata evidence; no installed-font substitution is treated as embedded metadata. */
public class PdfFontMetadataProbe {
    public static void main(String[] args) throws Exception {
        var output=new ArrayList<Object>();
        for (int i=1;i<args.length;i++) try(var pdf=Loader.loadPDF(Path.of(args[i]).toFile())) {
            var records=new ArrayList<Object>();
            for(var page:pdf.getPages()) for(var name:page.getResources().getFontNames()) {
                var font=page.getResources().getFont(name);var record=new LinkedHashMap<String,Object>();
                record.put("resource",name.getName());record.put("name",font.getName());record.put("embedded",font.isEmbedded());
                var descriptor=font.getFontDescriptor();record.put("descriptorFamily",descriptor==null?null:descriptor.getFontFamily());
                TrueTypeFont ttf=font instanceof PDTrueTypeFont simple?simple.getTrueTypeFont():
                        font instanceof PDType0Font type0 && type0.getDescendantFont() instanceof PDCIDFontType2 cid?cid.getTrueTypeFont():null;
                if(ttf!=null && font.isEmbedded()) {
                    record.put("embeddedFamily",ttf.getNaming().getFontFamily());record.put("embeddedSubfamily",ttf.getNaming().getFontSubFamily());
                    record.put("embeddedWeight",ttf.getOS2Windows()==null?null:ttf.getOS2Windows().getWeightClass());
                }
                if(font instanceof PDType0Font type0 && type0.getDescendantFont() instanceof PDCIDFontType0 cid && font.isEmbedded() && cid.getCFFFont()!=null) {
                    var dict=cid.getCFFFont().getTopDict();record.put("cffFamily",dict.get("FamilyName"));record.put("cffWeight",dict.get("Weight"));record.put("cffItalicAngle",dict.get("ItalicAngle"));
                }
                records.add(record);
            }
            output.add(Map.of("file",Path.of(args[i]).getFileName().toString(),"fonts",records));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),output);
    }
}
