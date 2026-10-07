package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.*;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfdOcrWarningsTest {
    @TempDir Path temp;

    @Test
    void incompleteEnhancedImageKeepsItsWarningsWithoutMarkingTheCompleteNeighbor() throws Exception {
        DocumentModel source = twoScans();
        var result = recognize(source, "90");
        var page = result.pages().get(0);
        assertEquals(List.of("original 2026 more lines", "complete 00421"),
                page.textBlocks().stream().map(TextBlock::text).toList());
        assertEquals(1, page.warnings().stream().filter(w -> w.code() == WarningCode.OCR_APPLIED).count());
        assertEquals(.935, page.warnings().stream().filter(w -> w.code() == WarningCode.OCR_APPLIED)
                .findFirst().orElseThrow().confidence(), 1e-9);
        for (var code : List.of(WarningCode.OCR_IMAGE_ENHANCED, WarningCode.OCR_POSSIBLE_TEXT_OMISSION)) {
            var warnings = page.warnings().stream().filter(w -> w.code() == code).toList();
            assertEquals(1, warnings.size(), code.toString());
            assertTrue(warnings.get(0).message().startsWith("OFD 第 1 页图片 1"));
            assertFalse(warnings.get(0).message().contains("未采用"));
        }
        assertTrue(page.warnings().stream().noneMatch(w -> w.message().contains("图片 2")));
        assertScansAndCoordinates(source, page);
    }

    @Test
    void lowConfidenceImageIsNotHiddenByTheWeightedPageAverage() throws Exception {
        DocumentModel source = twoScans();
        var page = recognize(source, "62").pages().get(0);
        assertEquals(.785, page.warnings().stream().filter(w -> w.code() == WarningCode.OCR_APPLIED)
                .findFirst().orElseThrow().confidence(), 1e-9);
        var warnings = page.warnings().stream().filter(w -> w.code() == WarningCode.OCR_LOW_CONFIDENCE).toList();
        assertEquals(1, warnings.size());
        assertEquals(.60, warnings.get(0).confidence(), 1e-9);
        assertTrue(warnings.get(0).message().startsWith("OFD 第 1 页图片 1"));
        assertTrue(page.warnings().stream().noneMatch(w -> w.code() == WarningCode.OCR_IMAGE_ENHANCED));
        assertScansAndCoordinates(source, page);
    }

    private DocumentModel recognize(DocumentModel source, String enhancedConfidence) throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path engine = temp.resolve("controlled-engine");
        Files.writeString(engine, """
                #!/bin/sh
                confidence=60
                text='original 2026'
                coordinates='50\t50\t400\t100'
                case "$1" in
                  *image-0002.png) confidence=97; text='complete 00421'; coordinates='0\t0\t600\t400' ;;
                  *tesseract-enhanced-*) confidence=ENHANCED; text='original 2026 more lines' ;;
                esac
                printf 'level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n' > "$2.tsv"
                printf '5\t1\t1\t1\t1\t1\t%s\t%s\t%s\n' "$coordinates" "$confidence" "$text" >> "$2.tsv"
                """.replace("ENHANCED", enhancedConfidence));
        assertTrue(engine.toFile().setExecutable(true));
        var settings = new TesseractOcrConverter.Settings(engine, "eng", "controlled", Duration.ofSeconds(10),
                1, .35, .75, 25_000_000, temp.resolve("locks"));
        return new OfdOcrSupport(settings).recognizeRequiredPages(source, temp.resolve("work"),
                ParseLimits.defaults(), (stage, percent) -> { });
    }

    private DocumentModel twoScans() throws Exception {
        var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) for (int x = 0; x < 600; x++) {
            int gray = 90 + x * 130 / 600;
            if (y >= 60 && y < 350 && y % 60 < 10 && x > 60 && x < 450 && x % 20 < 12) gray = 0;
            image.setRGB(x, y, new Color(gray, gray, gray).getRGB());
        }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); image.flush();
        var images = List.of(new ImageBlock("first", 1, new Rect(10, 10, 600, 400), "image/png", bytes.toByteArray(), "IMAGE", 1),
                new ImageBlock("second", 1, new Rect(630, 10, 600, 400), "image/png", bytes.toByteArray(), "IMAGE", 2));
        var page = new PageModel(1, new Rect(0, 0, 1240, 420), List.of(), List.of(), images, List.of(), List.of(),
                List.of(ConversionWarning.of(WarningCode.OCR_REQUIRED, "scan", 1)));
        return new DocumentModel("controlled.ofd", "controlled", 1, List.of(page), List.of());
    }

    private void assertScansAndCoordinates(DocumentModel source, PageModel page) {
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(source.pages().get(0).images().get(i).data(), page.images().get(i).data());
            assertEquals("OCR_SCAN_BACKGROUND", page.images().get(i).role());
            var box = source.pages().get(0).images().get(i).box();
            for (var word : page.textBlocks().get(i).ocrWords()) {
                assertTrue(word.box().x() >= box.x() && word.box().y() >= box.y());
                assertTrue(word.box().right() <= box.right() && word.box().bottom() <= box.bottom());
            }
        }
    }
}
