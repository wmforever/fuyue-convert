package com.fuyue.formatconverter.task;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class PdfSupport {
    private static final float MARGIN = 48f;
    private static final float FONT_SIZE = 11f;
    private static final float LEADING = 15f;

    private PdfSupport() {}

    static void writeTextPdf(List<String> lines, Path outputPath) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream content = beginPage(document, page, font);
            float y = page.getMediaBox().getHeight() - MARGIN;
            for (String raw : lines) {
                for (String line : wrap(raw == null ? "" : raw, 92)) {
                    if (y < MARGIN) {
                        content.endText();
                        content.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        content = beginPage(document, page, font);
                        y = page.getMediaBox().getHeight() - MARGIN;
                    }
                    content.showText(clean(line));
                    content.newLineAtOffset(0, -LEADING);
                    y -= LEADING;
                }
            }
            content.endText();
            content.close();
            document.save(outputPath.toFile());
        }
    }

    private static PDPageContentStream beginPage(PDDocument document, PDPage page, PDFont font) throws IOException {
        PDPageContentStream content = new PDPageContentStream(document, page);
        content.beginText();
        content.setFont(font, FONT_SIZE);
        content.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);
        content.setLeading(LEADING);
        return content;
    }

    private static PDFont loadFont(PDDocument document) {
        for (String candidate : List.of(
                "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                "/System/Library/Fonts/SFNS.ttf",
                "/Library/Fonts/Arial Unicode.ttf")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                try {
                    return PDType0Font.load(document, path.toFile());
                } catch (IOException ignored) {
                    // Fall through to the built-in Latin font.
                }
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static List<String> wrap(String line, int maxChars) {
        if (line.length() <= maxChars) return List.of(line);
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (int start = 0; start < line.length(); start += maxChars) {
            result.add(line.substring(start, Math.min(line.length(), start + maxChars)));
        }
        return result;
    }

    private static String clean(String value) {
        return value.replace('\t', ' ').replaceAll("[\\p{Cntrl}&&[^\\r\\n]]", "");
    }
}
