package com.fuyue.formatconverter.docx;

import com.fuyue.formatconverter.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class OcrOverlaySafetyTest {
    @TempDir Path temp;
    @Test void rejectsOverlappingGridRecognitionAndRetainsOriginalWithExplicitPartialEditWarning() throws Exception {
        var bad = new TextBlock.OcrWord(new Rect(20, 30, 60, 15), "ORR", .1);
        var good = new TextBlock.OcrWord(new Rect(22, 31, 12, 4), "DATA", .98);
        PageModel original = page(List.of(bad, good), false);
        PageModel safe = OcrOverlaySafety.prepare(original);
        assertTrue(safe.textBlocks().isEmpty(), "Do not leave half a scanned line mixed with new fonts");
        assertArrayEquals(original.images().get(0).data(), safe.images().get(0).data());
        var warning = safe.warnings().get(0);
        assertEquals(WarningCode.OCR_REGION_RETAINED_AS_IMAGE, warning.code());
        assertTrue(warning.message().contains("ORR"));
        assertTrue(warning.message().contains("本页未生成可编辑文字"));
        Path output = temp.resolve("partial.docx");
        new PoiDocxRenderer().render(new DocumentModel("scan", "test", 1, List.of(safe), List.of()), output);
        try (var docx = new XWPFDocument(Files.newInputStream(output))) {
            assertTrue(docx.getProperties().getCoreProperties().getDescription().contains("这些区域不可直接编辑"));
            assertArrayEquals(original.images().get(0).data(), docx.getAllPictures().get(0).getData());
            assertFalse(docx.getDocument().xmlText().contains("ORR"), "No hidden or visible erroneous OCR copy");
        }
    }

    @Test void stampCrossingWordIsPreservedAsSourceRatherThanDoubledByOcr() throws Exception {
        var uncertain = new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "STAMP", .98);
        PageModel original = page(List.of(uncertain), true);
        PageModel safe = OcrOverlaySafety.prepare(original);
        assertTrue(safe.textBlocks().isEmpty());
        assertArrayEquals(original.images().get(0).data(), safe.images().get(0).data());
        assertEquals(1, safe.warnings().size());
    }

    @Test void reliableDisjointWordsAndNativePagesAreUnchanged() throws Exception {
        var a = new TextBlock.OcrWord(new Rect(20, 30, 12, 4), "DATA", .98);
        var b = new TextBlock.OcrWord(new Rect(50, 30, 12, 4), "NEXT", .98);
        PageModel original = page(List.of(a, b), false);
        assertSame(original, OcrOverlaySafety.prepare(original));
        var nativeText = new TextBlock("native", 1, new Rect(20, 30, 12, 4), "NATIVE", 34, FontStyle.defaults(), 1);
        var nativePage = new PageModel(1, original.physicalBox(), List.of(nativeText), List.of(), original.images(), List.of(), List.of(), List.of());
        assertSame(nativePage, OcrOverlaySafety.prepare(nativePage));
    }

    private PageModel page(List<TextBlock.OcrWord> words, boolean stamp) throws Exception {
        var image = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 1000, 1000);
        if (stamp) { g.setColor(Color.RED); g.fillRect(230, 308, 20, 20); } g.dispose();
        var data = new ByteArrayOutputStream(); ImageIO.write(image, "png", data); image.flush();
        var scan = new ImageBlock("scan", 1, new Rect(0, 0, 100, 100), "image/png", data.toByteArray(), "OCR_PAGE_BACKGROUND", Integer.MIN_VALUE);
        var line = new TextBlock("ocr", 1, words.stream().map(TextBlock.OcrWord::box).reduce(Rect::union).orElseThrow(),
                words.stream().map(TextBlock.OcrWord::text).reduce((a, b) -> a + " " + b).orElseThrow(), 34,
                FontStyle.defaults(), 1, 0, 0, List.of(), Transform2D.IDENTITY, words);
        return new PageModel(1, scan.box(), List.of(line), List.of(), List.of(scan), List.of(), List.of(), List.of());
    }
}
