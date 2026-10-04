package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Production OFD support with a controlled engine; real engine tested via HTTP. */
class OfdNumericDedupTest {
    @TempDir Path temp;
    @Test void retainsBothSourceValuesWithImageScopedConflictWarningAndOriginalCoordinates() throws Exception {
        var source=source("Amount 048.65",true);var page=recognize(source,"Amount -048.65").pages().get(0);
        assertEquals(List.of("Amount 048.65","Amount -048.65"),page.textBlocks().stream().map(TextBlock::text).toList());
        assertEquals(source.pages().get(0).textBlocks().get(0),page.textBlocks().get(0));
        assertEquals(new Rect(50,50,100,30),page.textBlocks().get(1).box());
        assertEquals(new Rect(50,50,100,30),page.textBlocks().get(1).ocrWords().get(0).box());
        assertArrayEquals(source.pages().get(0).images().get(0).data(),page.images().get(0).data());
        assertEquals(source.pages().get(0).images().get(0).box(),page.images().get(0).box());
        assertEquals("OCR_SCAN_BACKGROUND",page.images().get(0).role());
        var conflict=page.warnings().stream().filter(w->w.code()==WarningCode.OCR_RECOGNITION_CONFLICT).toList();
        assertEquals(1,conflict.size());assertTrue(conflict.get(0).message().startsWith("OFD 第 1 页图片 1"));
        assertEquals(1,page.warnings().stream().filter(w->w.code()==WarningCode.OCR_APPLIED).count());
        assertTrue(page.warnings().stream().noneMatch(w->w.code()==WarningCode.OCR_REQUIRED));
    }
    @Test void exactDuplicateStillFailsStrictNoveltyGateInsteadOfReturningAnEmptySuccess() throws Exception {
        var source=source("Amount 048.65",true);
        assertEquals(1,ScannedContentDetector.imagesRequiringOcr(source.pages().get(0).textBlocks(),source.pages().get(0).images(),source.pages().get(0).physicalBox()).size());
        var failure=assertThrows(ConversionFailureException.class,()->recognize(source,"Amount 048.65"));
        assertEquals("OCR_NO_NEW_TEXT",failure.code());
    }
    @Test void nativeOnlyNoOpKeepsPageAndDoesNotInvokeTheConfiguredEngine() throws Exception {
        var source=source("Amount 048.65",false);var result=recognize(source,"SHOULD NOT RUN");
        assertEquals(source.pages(),result.pages());assertFalse(Files.exists(temp.resolve("invoked")));
    }
    private DocumentModel source(String nativeValue,boolean needsOcr) throws Exception {
        var pixels=new BufferedImage(600,400,BufferedImage.TYPE_INT_RGB);var g=pixels.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,600,400);g.setColor(Color.BLACK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,28));
        g.drawString("Amount -048.65 Document",50,90);g.drawString("Date 2076-10-14 Record",50,230);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(pixels,"png",bytes);pixels.flush();
        var nativeText=new TextBlock("native",1,new Rect(50,50,100,30),nativeValue,80,new FontStyle("Sans",12,false,false,ColorValue.BLACK),0);
        var image=new ImageBlock("scan",1,new Rect(0,0,600,400),"image/png",bytes.toByteArray(),"IMAGE",1);
        var page=new PageModel(1,new Rect(0,0,600,400),List.of(nativeText),List.of(),needsOcr?List.of(image):List.of(),List.of(),List.of(),
                needsOcr?List.of(ConversionWarning.of(WarningCode.OCR_REQUIRED,"scan",1)):List.of());
        return new DocumentModel("controlled.ofd","controlled",1,List.of(page),List.of());
    }
    private DocumentModel recognize(DocumentModel source,String value) throws Exception {
        assumeTrue(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path engine=temp.resolve("engine");
        Files.writeString(engine,"""
                #!/bin/sh
                touch "$(dirname "$0")/invoked"
                printf 'level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n' > "$2.tsv"
                printf '5\t1\t1\t1\t1\t1\t50\t50\t100\t30\t99\tVALUE\n' >> "$2.tsv"
                """.replace("VALUE",value));assertTrue(engine.toFile().setExecutable(true));
        var settings=new TesseractOcrConverter.Settings(engine,"eng","controlled",Duration.ofSeconds(10),1,.35,.75,25_000_000,temp.resolve("locks"));
        return new OfdOcrSupport(settings).recognizeRequiredPages(source,temp.resolve("work"),ParseLimits.defaults(),(s,p)->{});
    }
}
