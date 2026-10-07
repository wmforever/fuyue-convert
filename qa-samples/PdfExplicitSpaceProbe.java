package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Capture actual source characters, PDFBox emitted runs and application assembly. */
public class PdfExplicitSpaceProbe extends PDFTextStripper {
    final List<Object> raw=new ArrayList<>(),emitted=new ArrayList<>();
    PdfExplicitSpaceProbe() throws IOException { setSortByPosition(true);setShouldSeparateByBeads(false);setSuppressDuplicateOverlappingText(true); }
    Map<String,Object> position(TextPosition p) {
        return Map.of("text",p.getUnicode(),"x",p.getXDirAdj(),"y",p.getYDirAdj(),"width",p.getWidthDirAdj(),
            "font",p.getFont().getName(),"size",p.getFontSizeInPt(),"direction",p.getDir(),"spaceWidth",p.getWidthOfSpace());
    }
    @Override protected void processTextPosition(TextPosition p) {raw.add(position(p));super.processTextPosition(p);}
    @Override protected void writeString(String text,List<TextPosition> positions) throws IOException {
        emitted.add(Map.of("text",text,"positions",positions.stream().map(this::position).toList()));super.writeString(text,positions);
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);var rows=new ArrayList<Object>();
        for(int i=1;i<args.length;i++) {
            Path source=Path.of(args[i]);var probe=new PdfExplicitSpaceProbe();String standard;
            try(var doc=Loader.loadPDF(source.toFile())) {standard=probe.getText(doc);}
            var parser=new PdfLayoutParser();var sections=new PdfTextSectionOrder();
            var parsed=parser.parseForTextSections(source,source.getFileName().toString(),ParseLimits.defaults(),true,sections);
            var pages=parsed.pages().stream().map(new PageLayoutAnalyzer()::analyze).toList();
            rows.add(Map.of("source",source.toString(),"raw",probe.raw,"emitted",probe.emitted,"standardPdfBox",standard,
                "native",parsed,"assembled",sections.text(pages)));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),rows);
    }
}
