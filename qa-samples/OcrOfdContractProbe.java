package com.fuyue.formatconverter.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.parser.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Actual OFD parser/support, frozen controlled engine. Does not alter production gates. */
public class OcrOfdContractProbe {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),engine=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
        var limits=ParseLimits.defaults();
        var parsed=new OfdrwParser().parse(new SafeOfdExtractor().extract(source,out.resolve("extract"),limits),source.getFileName().toString(),limits);
        var settings=new TesseractOcrConverter.Settings(engine,"eng","controlled",Duration.ofSeconds(120),1,
                .35,.75,25_000_000,out.resolve("slots"));
        var result=new OfdOcrSupport(settings).recognizeRequiredPages(parsed,out.resolve("work"),limits,(stage,percent)->{});
        var pages=new ArrayList<Object>();
        for(var page:result.pages()) {
            var images=new ArrayList<Object>();
            for(var image:page.images()) images.add(Map.of("id",image.id(),"box",image.box(),"role",image.role(),
                "sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.data()))));
            pages.add(Map.of("pageNumber",page.pageNumber(),"physicalBox",page.physicalBox(),
                "blocks",page.textBlocks(),"images",images,"warnings",page.warnings()));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("model.json").toFile(),Map.of("pages",pages));
        System.out.println(source.getFileName()+" pages="+pages.size()+" blocks="+result.pages().stream().mapToInt(p->p.textBlocks().size()).sum());
    }
}
