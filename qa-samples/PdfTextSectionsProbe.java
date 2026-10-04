package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import java.nio.file.*;
import java.util.*;

/** Read-only extraction grouping and unchanged-native-geometry evidence. */
public class PdfTextSectionsProbe {
    public static void main(String[] args) throws Exception {
        var results=new ArrayList<Object>();
        for(int i=1;i<args.length;i++) {
            Path source=Path.of(args[i]);var parser=new PdfLayoutParser();var order=new PdfTextSectionOrder();
            var parsed=parser.parseForTextSections(source,source.getFileName().toString(),ParseLimits.defaults(),false,order);
            var legacy=parser.parseForTextExtraction(source,source.getFileName().toString(),ParseLimits.defaults());
            if(parsed.pages().size()!=legacy.pages().size())throw new AssertionError("page count");
            for(int p=0;p<parsed.pages().size();p++) {
                if(!parsed.pages().get(p).textBlocks().equals(legacy.pages().get(p).textBlocks())
                        ||!parsed.pages().get(p).physicalBox().equals(legacy.pages().get(p).physicalBox()))
                    throw new AssertionError("native geometry/text changed");
            }
            var field=PdfTextSectionOrder.class.getDeclaredField("blockGroups");field.setAccessible(true);
            var tags=PdfTextSectionOrder.class.getDeclaredField("markedGroups");tags.setAccessible(true);
            var pages=parsed.pages().stream().map(new PageLayoutAnalyzer()::analyze).toList();
            results.add(Map.of("file",source.getFileName().toString(),"originalNativeTextAndGeometryExact",true,
                    "blockGroups",field.get(order),"markedGroups",tags.get(order),
                    "legacy",OfdToTextConverter.text(pages),"tagged",order.text(pages)));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),results);
    }
}
