package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextInputReaderTest {
    @TempDir Path temp;

    @Test
    void readsUtf8BomUtf16BomAndPreservesExplicitPages() throws Exception {
        Path utf8 = temp.resolve("utf8.txt");
        Files.write(utf8, withPrefix(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                "第一页\r\n第二行\f末页".getBytes(StandardCharsets.UTF_8)));
        var decodedUtf8 = TextInputReader.read(utf8, ParseLimits.defaults());
        assertEquals("UTF-8", decodedUtf8.charsetName());
        assertEquals(2, decodedUtf8.pages().size());
        assertEquals("第二行", decodedUtf8.pages().get(0).get(1));
        assertEquals("末页", decodedUtf8.pages().get(1).get(0));

        Path utf16 = temp.resolve("utf16.txt");
        Files.write(utf16, withPrefix(new byte[]{(byte) 0xff, (byte) 0xfe},
                "中文 UTF16".getBytes(StandardCharsets.UTF_16LE)));
        var decodedUtf16 = TextInputReader.read(utf16, ParseLimits.defaults());
        assertEquals("UTF-16LE", decodedUtf16.charsetName());
        assertEquals("中文 UTF16", decodedUtf16.pages().get(0).get(0));
    }

    @Test
    void fallsBackToStrictGb18030WithVisibleWarning() throws Exception {
        Path source = temp.resolve("gb18030.txt");
        Files.write(source, "简体中文编码".getBytes(Charset.forName("GB18030")));

        var decoded = TextInputReader.read(source, ParseLimits.defaults());

        assertEquals("GB18030", decoded.charsetName());
        assertEquals("简体中文编码", decoded.pages().get(0).get(0));
        assertEquals(WarningCode.TEXT_ENCODING_GUESSED, decoded.warnings().get(0).code());
    }

    @Test
    void rejectsBinaryControlContentAndTooManyExplicitPages() throws Exception {
        Path binary = temp.resolve("binary.txt");
        Files.write(binary, new byte[20]);
        ConversionFailureException binaryFailure = assertThrows(ConversionFailureException.class,
                () -> TextInputReader.read(binary, ParseLimits.defaults()));
        assertEquals("TEXT_BINARY_CONTENT", binaryFailure.code());

        Path pages = temp.resolve("pages.txt");
        Files.writeString(pages, "一\f二\f三", StandardCharsets.UTF_8);
        ParseLimits twoPages = new ParseLimits(1024, 4096, 4096, 10, 100, 2);
        ConversionFailureException pageFailure = assertThrows(ConversionFailureException.class,
                () -> TextInputReader.read(pages, twoPages));
        assertEquals("PAGE_LIMIT_EXCEEDED", pageFailure.code());
    }

    private byte[] withPrefix(byte[] prefix, byte[] value) {
        byte[] result = new byte[prefix.length + value.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(value, 0, result, prefix.length, value.length);
        return result;
    }
}
