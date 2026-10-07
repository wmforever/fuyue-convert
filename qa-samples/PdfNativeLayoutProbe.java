import com.fuyue.formatconverter.task.PdfLayoutParser;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Read-only native-block evidence, before paragraph reconstruction/rendering. */
public class PdfNativeLayoutProbe {
    public static void main(String[] args) throws Exception {
        var records = new ArrayList<Object>();
        for (int i=1;i<args.length;i++) {
            var path=Path.of(args[i]);
            var parsed=new PdfLayoutParser().parse(path,path.getFileName().toString(),ParseLimits.defaults());
            var analyzed=parsed.pages().stream().map(new PageLayoutAnalyzer()::analyze).toList();
            records.add(Map.of("file",path.getFileName().toString(),"parsed",parsed,"analyzed",analyzed));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),records);
    }
}
