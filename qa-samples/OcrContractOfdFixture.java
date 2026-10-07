// Apache-2.0 public controlled fixture. Image order is stable within each page.
import java.nio.file.Path;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Position;

class OcrContractOfdFixture {
    public static void main(String[] args) throws Exception {
        boolean multi=args[1].equals("multi"); double size=1200*25.4/300;
        try(var doc=new OFDDoc(Path.of(args[0]))) {
            for(int p=0;p<(multi?2:1);p++) {
                var page=new VirtualPage(2*size+30,multi?size+20:2*size+30);
                int count=multi?2:4;
                for(int i=0;i<count;i++) {
                    int index=multi?p*2+i:i;
                    var image=new Img(size,size,Path.of(args[index+2]));
                    image.setPosition(Position.Absolute).setBox(10+(i%2)*(size+10),10+(i/2)*(size+10),size,size);
                    page.add(image);
                }
                doc.addVPage(page);
            }
        }
    }
}
