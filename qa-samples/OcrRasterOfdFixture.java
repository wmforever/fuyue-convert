// Apache-2.0 synthetic fixture generator; uses the application's pinned OFDRW.
import java.nio.file.Path;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Position;

class OcrRasterOfdFixture {
    public static void main(String[] args) throws Exception {
        double width = Integer.parseInt(args[2]) * 25.4 / 300;
        double height = Integer.parseInt(args[3]) * 25.4 / 300;
        var image = new Img(width, height, Path.of(args[0]));
        image.setPosition(Position.Absolute).setBox(0d, 0d, width, height);
        try (var document = new OFDDoc(Path.of(args[1]))) {
            document.addVPage(new VirtualPage(width, height).add(image));
        }
    }
}
