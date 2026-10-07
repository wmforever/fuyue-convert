package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import java.nio.file.*;
import java.util.*;

/** No conversion/OCR: retain actual source text, rules, cells and body ownership. */
public final class PdfNativeTablesProbe44 {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectory(out);var json=new ObjectMapper();
        for(int i=1;i<args.length;i++) {
            Path source=Path.of(args[i]);var raw=new PdfLayoutParser().parse(source,source.getFileName().toString(),ParseLimits.defaults());
            var analyzed=raw.pages().stream().map(new PageLayoutAnalyzer()::analyze).toList();
            json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(source.getFileName()+".json").toFile(),Map.of("source",source.toString(),"raw",raw,"analyzedPages",analyzed,"newOCR",0));
            System.out.println(source.getFileName()+" pages="+raw.pages().size()+" tables="+analyzed.stream().mapToInt(p->p.tables().size()).sum());
        }
    }
}
