package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfdOcrPartialWarningsTest {
    @TempDir Path temp;
    @Test void samePagePreservesPartialAndRejectedNumericWordsWithImageScopedWarnings() throws Exception { verify(false); }
    @Test void multiplePagesKeepSeparateWeightedSummariesAndImageScopes() throws Exception { verify(true); }

    private void verify(boolean multi) throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        var raster=new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB);
        var g=raster.createGraphics();g.setColor(new Color(160,160,160));g.fillRect(0,0,200,200);
        g.setColor(new Color(130,130,130));g.fillRect(20,80,140,12);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(raster,"png",bytes);raster.flush();
        List<PageModel> pages=new ArrayList<>();
        for(int p=1;p<=(multi?2:1);p++) {
            List<ImageBlock> images=new ArrayList<>();
            for(int i=0;i<(multi?2:4);i++) images.add(new ImageBlock("scan"+i,p,
                    new Rect(10+(i%2)*210,10+(i/2)*210,200,200),"image/png",bytes.toByteArray(),"IMAGE",i));
            pages.add(new PageModel(p,new Rect(0,0,430,multi?220:430),List.of(),List.of(),images,List.of(),List.of(),
                    List.of(ConversionWarning.of(WarningCode.OCR_REQUIRED,"scan",p))));
        }
        Path engine=temp.resolve("controlled-engine");
        Files.writeString(engine,"""
                #!/bin/sh
                index=$(basename "$(dirname "$2")")
                page=$(basename "$(dirname "$(dirname "$2")")")
                name=full
                case "$index" in ocr-2) name=partial;; ocr-3) name=reject;; ocr-4) name=low;; esac
                if [ MULTI = 1 ] && [ "$page" = page-0002 ]; then
                  case "$index" in ocr-1) name=reject;; ocr-2) name=low;; esac
                fi
                retry=no
                case "$1" in *tesseract-enhanced-*) retry=yes;; esac
                printf 'level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n' > "$2.tsv"
                case "$name-$retry" in
                  full-no) printf '5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t60\tFULL03121\n';;
                  full-yes) printf '5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t98\tFULL03121\n5\t1\t1\t1\t2\t1\t10\t60\t80\t10\t98\tIndependent full prose 00643\n';;
                  partial-no) printf '5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t96\t.95\n5\t1\t1\t1\t1\t2\t50\t10\t20\t10\t20\tfaint\n';;
                  partial-yes) printf '5\t1\t1\t1\t1\t1\t10\t10\t60\t10\t98\t0.95 corrected\n5\t1\t1\t1\t2\t1\t10\t60\t80\t10\t98\tRecovered independent prose 00817\n';;
                  reject-no) printf '5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t96\tID00424\n5\t1\t1\t1\t1\t2\t50\t10\t20\t10\t20\tfaint\n';;
                  reject-yes) printf '5\t1\t1\t1\t1\t1\t10\t10\t60\t10\t98\tID80424 corrected\n';;
                  low-no) printf '5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t60\tLOW00424\n';;
                  low-yes) printf '5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t62\tLOW00424\n';;
                esac >> "$2.tsv"
                """.replace("MULTI",multi?"1":"0"));
        assertTrue(engine.toFile().setExecutable(true));
        var source=new DocumentModel("controlled.ofd","test",pages.size(),pages,List.of());
        var settings=new TesseractOcrConverter.Settings(engine,"eng","controlled",Duration.ofSeconds(10),1,
                .35,.75,25_000_000,temp.resolve("slots"));
        var result=new OfdOcrSupport(settings).recognizeRequiredPages(source,temp.resolve("work"),ParseLimits.defaults(),(s,p)->{});
        for(var page:result.pages()) {
            List<String> names=multi?(page.pageNumber()==1?List.of("full","partial"):List.of("reject","low"))
                    :List.of("full","partial","reject","low");
            for(int i=0;i<names.size();i++) {
                int index=i+1;String name=names.get(i);
                var blocks=page.textBlocks().stream().filter(b->b.id().endsWith("-i"+index)).toList();
                var words=blocks.stream().flatMap(b->b.ocrWords().stream()).toList();
                var texts=words.stream().map(TextBlock.OcrWord::text).toList();
                assertEquals(switch(name) {
                    case "full" -> List.of("FULL03121","Independent full prose 00643");
                    case "partial" -> List.of(".95","faint","Recovered independent prose 00817");
                    case "reject" -> List.of("ID00424","faint");default -> List.of("LOW00424");
                },texts);
                var image=page.images().get(i);assertArrayEquals(bytes.toByteArray(),image.data());
                assertEquals("OCR_SCAN_BACKGROUND",image.role());
                assertEquals(new Rect(image.box().x()+10,image.box().y()+10,30,10),words.get(0).box());
                assertEquals(name.equals("full")?.98:name.equals("low")?.60:.96,words.get(0).confidence(),1e-9);
                if(name.equals("partial")||name.equals("reject")) {
                    assertEquals(.20,words.get(1).confidence(),1e-9);
                    assertEquals(new Rect(image.box().x()+50,image.box().y()+10,20,10),words.get(1).box());
                }
                String scope="OFD 第 "+page.pageNumber()+" 页图片 "+index;
                var warnings=page.warnings().stream().filter(w->w.message().startsWith(scope)).toList();
                assertEquals(name.equals("full")||name.equals("partial")?1:0,
                        warnings.stream().filter(w->w.code()==WarningCode.OCR_IMAGE_ENHANCED).count());
                for(var code:List.of(WarningCode.OCR_RECOGNITION_CONFLICT,WarningCode.OCR_POSSIBLE_TEXT_OMISSION))
                    assertEquals(name.equals("partial")?1:0,warnings.stream().filter(w->w.code()==code).count());
                if(name.equals("partial")) assertTrue(warnings.stream().filter(w->w.code()==WarningCode.OCR_IMAGE_ENHANCED)
                        .allMatch(w->w.message().contains("严格分离的新行")));
                else assertTrue(warnings.stream().noneMatch(w->w.message().contains("严格分离的新行")));
            }
            var applied=page.warnings().stream().filter(w->w.code()==WarningCode.OCR_APPLIED).toList();
            assertEquals(1,applied.size());
            var words=page.textBlocks().stream().flatMap(b->b.ocrWords().stream()).toList();
            assertEquals(words.stream().mapToDouble(TextBlock.OcrWord::confidence).average().orElseThrow(),
                    applied.get(0).confidence(),1e-9,"page summary must weight words, not images");
        }
    }
}
