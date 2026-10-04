// Apache-2.0 synthetic fixture; scans are retained at their original 300-DPI scale.
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Position;

class OcrMultiRasterOfdFixture {
    public static void main(String[] args) throws Exception {
        double width = 0, height = 0;
        for (int i = 1; i < args.length; i++) {
            var image = ImageIO.read(Path.of(args[i]).toFile());
            width = Math.max(width, image.getWidth() * 25.4 / 300);
            height += image.getHeight() * 25.4 / 300;
            image.flush();
        }
        var page = new VirtualPage(width, height);
        double y = 0;
        for (int i = 1; i < args.length; i++) {
            var pixels = ImageIO.read(Path.of(args[i]).toFile());
            double w = pixels.getWidth() * 25.4 / 300, h = pixels.getHeight() * 25.4 / 300;
            var image = new Img(w, h, Path.of(args[i]));
            image.setPosition(Position.Absolute).setBox(0d, y, w, h);
            page.add(image); y += h; pixels.flush();
        }
        try (var document = new OFDDoc(Path.of(args[0]))) { document.addVPage(page); }
    }
}
