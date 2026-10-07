import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
/** Reproduce the unchanged 300-DPI PDF OCR background for pixel preservation checks. */
class PdfReviewRasterProbe {
    public static void main(String[] args) throws Exception {
        try (var doc=Loader.loadPDF(Path.of(args[0]).toFile())) {
            if(doc.getNumberOfPages()!=1)throw new AssertionError("single-page smoke fixture required");
            ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(0,300,ImageType.RGB),"png",Path.of(args[1]).toFile());
        }
    }
}
