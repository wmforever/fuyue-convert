package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlToTextConverterTest {
    @TempDir Path temp;

    @Test
    void extractsVisibleTextAndTablesWithoutExecutingHiddenContent() throws Exception {
        Path source = temp.resolve("page.html");
        Files.writeString(source, """
                <html><head><title>不要导出</title><script>window.pwned = true;</script>
                <style>.hidden { display:none }</style></head><body>
                <h1>标题</h1><p>第一段 <strong>正文</strong>。</p>
                <table><tr><th>姓名</th><th>部门</th></tr><tr><td>张三</td><td>研发</td></tr></table>
                <p class="hidden">仍然按文档流提取的文本</p><!-- 注释不应出现 -->
                </body></html>
                """, StandardCharsets.UTF_8);
        Path output = temp.resolve("page.txt");

        ConversionOutput result = new HtmlToTextConverter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });
        String text = Files.readString(output);

        assertEquals("page.txt", result.outputName());
        assertTrue(text.contains("标题\n第一段 正文。"), text);
        assertTrue(text.contains("姓名\t部门\n张三\t研发"), text);
        assertTrue(text.contains("仍然按文档流提取的文本"), text);
        assertFalse(text.contains("window.pwned"), text);
        assertFalse(text.contains("不要导出"), text);
        assertFalse(text.contains("注释不应出现"), text);
    }

    @Test
    void reportsStrictFallbackWhenHtmlUsesLegacyEncoding() throws Exception {
        Path source = temp.resolve("legacy.html");
        Files.write(source, "<p>中文页面</p>".getBytes(Charset.forName("GB18030")));
        Path output = temp.resolve("legacy.txt");

        ConversionOutput result = new HtmlToTextConverter().convert(input(source), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        assertEquals("中文页面\n", Files.readString(output));
        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.TEXT_ENCODING_GUESSED));
    }

    @Test
    void enforcesHtmlElementLimit() throws Exception {
        Path source = temp.resolve("large.html");
        Files.writeString(source, "<p>one</p><p>two</p>", StandardCharsets.UTF_8);
        Path output = temp.resolve("large.txt");
        ParseLimits tight = new ParseLimits(1024, 1024, 1024, 2, 100, 1);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                () -> new HtmlToTextConverter().convert(input(source), temp.resolve("work"), output,
                        tight, (stage, percent) -> { }));
        assertEquals("HTML_LIMIT_EXCEEDED", failure.code());
    }

    @Test
    void registryAdvertisesHtmlToTextRoute() {
        assertTrue(DefaultConverterRegistry.create(null, Duration.ofSeconds(1)).stream()
                .anyMatch(converter -> converter instanceof HtmlToTextConverter
                        && converter.route().id().equals("html-to-txt")));
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "text/html", Files.size(source), source);
    }
}
