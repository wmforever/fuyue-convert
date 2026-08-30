package com.fuyue.formatconverter.parser;

import com.fuyue.formatconverter.model.WarningCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfdrwParserSignatureTest {
    @TempDir Path temp;

    @Test void recognizesEmbeddedOfdSignatureWithoutAConversionDependency() {
        OfdrwParser parser = new OfdrwParser();
        assertTrue(parser.isEmbeddedOfdStamp(null, "OFD"));
        assertTrue(parser.isEmbeddedOfdStamp(new byte[]{'P', 'K', 3, 4}, null));
        assertFalse(parser.isEmbeddedOfdStamp(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, "PNG"));
        assertFalse(parser.isEmbeddedOfdStamp(null, null));
    }

    @Test void skipsEmbeddedOfdSignatureAppearanceWithExplicitWarning() throws Exception {
        Path fixture = Path.of("..", "qa-samples", "input", "ofdrw-invoice.ofd").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(fixture), "可选真实签章 OFD 回归样本未提供");

        SafeOfdPackage unpacked = new SafeOfdExtractor().extract(
                fixture, temp.resolve("invoice"), ParseLimits.defaults());
        var parsed = new OfdrwParser().parse(unpacked, "ofdrw-invoice.ofd", ParseLimits.defaults());

        assertTrue(parsed.warnings().stream()
                        .anyMatch(warning -> warning.code() == WarningCode.SIGNATURE_APPEARANCE_FAILED
                                && warning.message().contains("嵌套 OFD")
                                && warning.message().contains("已保留正文")),
                () -> "嵌套 OFD 签章必须给出明确降级提示: " + parsed.warnings());
        assertFalse(parsed.pages().stream().flatMap(page -> page.images().stream())
                .anyMatch(image -> "SIGNATURE".equals(image.role())),
                "未内置签章渲染器时不应伪造或误解码签章外观");
        assertFalse(parsed.pages().get(0).textBlocks().isEmpty(), "跳过签章外观不应丢失正文");
    }
}
