package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Extracts visible HTML text without creating a browser or following any URL.
 * ParserDelegator is deliberately used with a StringReader and an ignored
 * charset declaration, so parsing remains local and deterministic after the
 * input has been decoded by {@link TextInputReader}.
 */
public final class HtmlToTextConverter implements FileConverter {
    private static final Set<String> BLOCK_TAGS = Set.of(
            "address", "blockquote", "body", "caption", "center", "dd", "div", "dl", "dt",
            "fieldset", "figure", "figcaption", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "head", "hr", "html", "legend", "li", "main", "nav", "ol", "p", "pre", "section", "table",
            "tbody", "td", "tfoot", "th", "thead", "tr", "ul");
    private static final Set<String> HIDDEN_TAGS = Set.of("head", "script", "style", "noscript", "template");
    private static final Set<String> TABLE_CELL_TAGS = Set.of("td", "th");

    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.HTML, DocumentFormat.TXT,
            "提取 HTML 可见文本，并将段落和表格整理为 UTF-8 TXT；解析过程不执行脚本或加载外部资源。",
            QualityLevel.BETA, ConversionStrategy.EXTRACTION, List.of(),
            List.of("仅保留可见文本、基本段落和表格换行，不保留 HTML 样式或链接地址",
                    "脚本、样式、模板和注释内容会被忽略；复杂布局按文档流提取"));

    @Override
    public ConversionRoute route() {
        return route;
    }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        TextInputReader.DecodedContent decoded = TextInputReader.readContent(input.path(), limits);
        HtmlTextCallback callback = new HtmlTextCallback(limits);
        try {
            new ParserDelegator().parse(new StringReader(decoded.value()), callback, true);
        } catch (HtmlLimitExceeded e) {
            throw new ConversionFailureException("HTML_LIMIT_EXCEEDED", e.getMessage());
        }
        progress.update(TaskStage.RENDERING, 80);
        byte[] utf8 = callback.text().getBytes(StandardCharsets.UTF_8);
        if (utf8.length > limits.maxExpandedBytes()) {
            throw new ConversionFailureException("HTML_OUTPUT_TOO_LARGE",
                    "HTML 提取文本超过限制：" + utf8.length + " > " + limits.maxExpandedBytes());
        }
        Files.write(outputPath, utf8);
        ConversionGuards.requireOutputFile(outputPath, limits, "HTML 转 TXT");
        return new ConversionOutput(outputPath,
                input.displayName().replaceFirst("(?i)\\.html$", ".txt"), null,
                decoded.warnings());
    }

    private static final class HtmlTextCallback extends HTMLEditorKit.ParserCallback {
        private final ParseLimits limits;
        private final StringBuilder output = new StringBuilder();
        private int hiddenDepth;
        private int tableDepth;
        private int rowDepth;
        private boolean rowHasCell;
        private boolean insideCell;
        private int callbackCount;

        private HtmlTextCallback(ParseLimits limits) {
            this.limits = limits;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet attributes, int position) {
            countCallback();
            String name = tagName(tag);
            if (HIDDEN_TAGS.contains(name)) {
                hiddenDepth++;
                return;
            }
            if (hiddenDepth > 0) return;
            if ("table".equals(name)) {
                ensureBreak();
                tableDepth++;
                return;
            }
            if ("tr".equals(name)) {
                ensureBreak();
                rowDepth++;
                rowHasCell = false;
                return;
            }
            if (TABLE_CELL_TAGS.contains(name)) {
                if (rowHasCell) appendTab();
                rowHasCell = true;
                insideCell = true;
                return;
            }
            if (BLOCK_TAGS.contains(name)) ensureBreak();
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            countCallback();
            String name = tagName(tag);
            if (HIDDEN_TAGS.contains(name)) {
                if (hiddenDepth > 0) hiddenDepth--;
                return;
            }
            if (hiddenDepth > 0) return;
            if (TABLE_CELL_TAGS.contains(name)) {
                insideCell = false;
                return;
            }
            if ("tr".equals(name)) {
                ensureBreak();
                if (rowDepth > 0) rowDepth--;
                rowHasCell = false;
                insideCell = false;
                return;
            }
            if ("table".equals(name)) {
                ensureBreak();
                if (tableDepth > 0) tableDepth--;
                rowHasCell = false;
                insideCell = false;
                return;
            }
            if (BLOCK_TAGS.contains(name)) ensureBreak();
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet attributes, int position) {
            countCallback();
            if (hiddenDepth > 0) return;
            String name = tagName(tag);
            if ("br".equals(name)) {
                ensureBreak();
            } else if ("hr".equals(name)) {
                ensureBreak();
                appendText("---");
                ensureBreak();
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            countCallback();
            if (hiddenDepth == 0) appendText(new String(data));
        }

        private void countCallback() {
            if (++callbackCount > limits.maxEntries()) {
                throw new HtmlLimitExceeded("HTML 元素数量超过限制：" + callbackCount
                        + " > " + limits.maxEntries());
            }
        }

        private static String tagName(HTML.Tag tag) {
            return tag.toString().toLowerCase(java.util.Locale.ROOT);
        }

        private void appendText(String value) {
            String normalized = value.replace('\u00a0', ' ').replaceAll("[\\t\\r\\n\\f ]+", " ");
            if (normalized.isBlank()) return;
            if (output.length() == 0 || output.charAt(output.length() - 1) == '\n'
                    || output.charAt(output.length() - 1) == '\t') {
                normalized = normalized.stripLeading();
            }
            output.append(normalized);
            if (output.length() > limits.maxExpandedBytes()) {
                throw new HtmlLimitExceeded("HTML 提取文本超过限制：" + output.length()
                        + " > " + limits.maxExpandedBytes());
            }
        }

        private void appendTab() {
            trimTrailingWhitespace();
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n'
                    && output.charAt(output.length() - 1) != '\t') output.append('\t');
        }

        private void ensureBreak() {
            trimTrailingWhitespace();
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
        }

        private void trimTrailingWhitespace() {
            while (output.length() > 0) {
                char last = output.charAt(output.length() - 1);
                if (last != ' ' && last != '\t') break;
                output.setLength(output.length() - 1);
            }
        }

        private String text() {
            String value = output.toString().replaceAll("\\n{3,}", "\\n").strip();
            return value.isEmpty() ? value : value + "\n";
        }
    }

    private static final class HtmlLimitExceeded extends RuntimeException {
        private HtmlLimitExceeded(String message) {
            super(message);
        }
    }
}
