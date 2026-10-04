import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.nio.file.Path;
import java.util.List;

/** Report selected versus Office's Arial-compatible font metrics; no mutation. */
public class OcrHeadingMetricsProbe {
    public static void main(String[] args) throws Exception {
        var context = new FontRenderContext(null, true, true);
        var requested = new Font("Arial", Font.PLAIN, 100);
        var compatible = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[0]).toFile()).deriveFont(100f);
        for (var font : List.of(requested, compatible)) {
            System.out.println("requested="+font.getName()+" family="+font.getFamily()+" actual="+font.getFontName());
            for (String text : List.of("RESERVE", "RESERVE ", "RECORD", "PROJECT", "SUMMARY")) {
                var glyphs=font.deriveFont(13.5f).createGlyphVector(context,text);
                System.out.println(text+" inkWidthPt="+glyphs.getVisualBounds().getWidth()*1.19+
                    " advancePt="+glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX()*1.19);
            }
        }
    }
}
