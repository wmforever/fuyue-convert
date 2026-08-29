package com.fuyue.formatconverter.parser;

import com.fuyue.formatconverter.model.WarningCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfdrwParserSignatureTest {
    @TempDir Path temp;

    @Test void preservesEmbeddedOfdSignatureAppearanceWithPdfBox3Runtime() throws Exception {
        Path fixture = Path.of("..", "qa-samples", "input", "ofdrw-invoice.ofd").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(fixture), "真实签章 OFD 回归样本缺失");

        SafeOfdPackage unpacked = new SafeOfdExtractor().extract(
                fixture, temp.resolve("invoice"), ParseLimits.defaults());
        var parsed = new OfdrwParser().parse(unpacked, "ofdrw-invoice.ofd", ParseLimits.defaults());

        assertTrue(parsed.warnings().stream()
                        .noneMatch(warning -> warning.code() == WarningCode.SIGNATURE_APPEARANCE_FAILED),
                () -> "签章外观不应因 PDFBox ABI 冲突丢失: " + parsed.warnings());
        var signature = parsed.pages().get(0).images().stream()
                .filter(image -> "SIGNATURE".equals(image.role()))
                .findFirst().orElseThrow();
        assertEquals("image/png", signature.mimeType());
        var raster = ImageIO.read(new ByteArrayInputStream(signature.data()));
        assertNotNull(raster);
        assertTrue(raster.getWidth() > 10 && raster.getHeight() > 10);
        long visibleInk = 0;
        long transparent = 0;
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                int argb = raster.getRGB(x, y);
                int alpha = argb >>> 24;
                if (alpha == 0) transparent++;
                if (alpha > 0 && (argb & 0x00ffffff) != 0x00ffffff) visibleInk++;
            }
        }
        long pixels = (long) raster.getWidth() * raster.getHeight();
        assertTrue(visibleInk > pixels / 100, "签章 PNG 必须包含可见印章内容");
        assertTrue(transparent > pixels / 100, "签章背景必须保留透明度，不能盖住正文");
    }
}
