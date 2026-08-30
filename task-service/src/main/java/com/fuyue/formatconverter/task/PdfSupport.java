package com.fuyue.formatconverter.task;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class PdfSupport {
    private static final float MARGIN = 48f;
    private static final float FONT_SIZE = 11f;
    private static final float LEADING = 15f;
    private static final String PDF_FONT_ENV = "FORMAT_CONVERTER_PDF_FONT";
    private static final String BUNDLED_LATIN_FONT = "/fonts/LiberationSans-Regular.ttf";
    private static final String BUNDLED_CJK_FONT = "/fonts/DroidSansFallback.ttf";

    private PdfSupport() {}

    static int writeTextPdfPages(List<List<String>> pages, Path outputPath, int maxPages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            FontSet fonts = loadFonts(document);
            PDPageContentStream content = null;
            int pageCount = 0;
            List<List<String>> logicalPages = pages.isEmpty() ? List.of(List.of("")) : pages;
            for (List<String> lines : logicalPages) {
                if (content != null) closePage(content);
                PDPage page = addPage(document, ++pageCount, maxPages);
                content = beginPage(document, page, fonts.latin());
                float y = page.getMediaBox().getHeight() - MARGIN;
                for (String raw : lines) {
                    String cleaned = clean(raw == null ? "" : raw);
                    for (String line : wrap(cleaned, fonts, page.getMediaBox().getWidth() - (2 * MARGIN))) {
                        if (y < MARGIN) {
                            closePage(content);
                            page = addPage(document, ++pageCount, maxPages);
                            content = beginPage(document, page, fonts.latin());
                            y = page.getMediaBox().getHeight() - MARGIN;
                        }
                        showText(content, line, fonts);
                        content.newLineAtOffset(0, -LEADING);
                        y -= LEADING;
                    }
                }
            }
            if (content != null) closePage(content);
            document.save(outputPath.toFile());
            return pageCount;
        }
    }

    private static PDPage addPage(PDDocument document, int pageCount, int maxPages) throws IOException {
        if (pageCount > maxPages) {
            throw new ConversionFailureException("PAGE_LIMIT_EXCEEDED",
                    "文本排版后的 PDF 页数超过限制：" + pageCount + " > " + maxPages);
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        return page;
    }

    private static void closePage(PDPageContentStream content) throws IOException {
        content.endText();
        content.close();
    }

    private static PDPageContentStream beginPage(PDDocument document, PDPage page, PDFont font) throws IOException {
        PDPageContentStream content = new PDPageContentStream(document, page);
        content.beginText();
        content.setFont(font, FONT_SIZE);
        content.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);
        content.setLeading(LEADING);
        return content;
    }

    private static FontSet loadFonts(PDDocument document) throws IOException {
        PDFont latin = loadBundledFont(document, BUNDLED_LATIN_FONT);
        String configuredFont = System.getenv(PDF_FONT_ENV);
        if (configuredFont != null && !configuredFont.isBlank()) {
            Path path = Path.of(configuredFont).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException(PDF_FONT_ENV + " 指定的字体文件不存在：" + path);
            }
            return new FontSet(latin, PDType0Font.load(document, path.toFile()));
        }
        return new FontSet(latin, loadBundledFont(document, BUNDLED_CJK_FONT));
    }

    private static PDFont loadBundledFont(PDDocument document, String resource) throws IOException {
        try (InputStream input = PdfSupport.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("内置 PDF 字体缺失：" + resource);
            return PDType0Font.load(document, input);
        }
    }

    private static List<String> wrap(String line, FontSet fonts, float maxWidth) throws IOException {
        if (line.isEmpty()) return List.of("");
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        float width = 0;
        for (int offset = 0; offset < line.length();) {
            int codePoint = line.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Glyph glyph = glyphFor(codePoint, fonts);
            float glyphWidth = glyph.font().getStringWidth(glyph.text()) * FONT_SIZE / 1000f;
            if (!current.isEmpty() && width + glyphWidth > maxWidth) {
                result.add(current.toString());
                current.setLength(0);
                width = 0;
            }
            current.append(glyph.text());
            width += glyphWidth;
        }
        result.add(current.toString());
        return result;
    }

    private static void showText(PDPageContentStream content, String line, FontSet fonts) throws IOException {
        PDFont activeFont = null;
        StringBuilder run = new StringBuilder();
        for (int offset = 0; offset < line.length();) {
            int codePoint = line.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Glyph glyph = glyphFor(codePoint, fonts);
            if (activeFont != glyph.font()) {
                showRun(content, activeFont, run);
                activeFont = glyph.font();
            }
            run.append(glyph.text());
        }
        showRun(content, activeFont, run);
    }

    private static void showRun(PDPageContentStream content, PDFont font, StringBuilder run) throws IOException {
        if (font == null || run.isEmpty()) return;
        content.setFont(font, FONT_SIZE);
        content.showText(run.toString());
        run.setLength(0);
    }

    private static Glyph glyphFor(int codePoint, FontSet fonts) {
        String value = new String(Character.toChars(codePoint));
        if (canEncode(fonts.latin(), value)) return new Glyph(fonts.latin(), value);
        if (canEncode(fonts.cjk(), value)) return new Glyph(fonts.cjk(), value);
        return new Glyph(fonts.latin(), "?");
    }

    private static boolean canEncode(PDFont font, String value) {
        try {
            font.encode(value);
            return true;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        return value.replace('\t', ' ').replaceAll("[\\p{Cntrl}&&[^\\r\\n]]", "");
    }

    private record FontSet(PDFont latin, PDFont cjk) {}

    private record Glyph(PDFont font, String text) {}
}
