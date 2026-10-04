package com.fuyue.formatconverter.task;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.fontbox.ttf.TrueTypeFont;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates a PDF face name from the family and style properties used by Word. */
final class PdfFontNames {
    // Restrict aliases to these familiar metric-compatible families. Similar names such as
    // Arial Narrow, Arial Black and Helvetica Neue represent distinct faces and must survive.
    private static final Pattern COMMON_FACE = Pattern.compile(
            "(?i)^(arial|helvetica|timesnewroman|times|couriernew|courier)(?:ps)?"
                    + "(regular|roman|bolditalic|boldoblique|bold|italic|oblique)?(?:mt)?$");
    private static final Pattern STYLE_SUFFIX = Pattern.compile(
            "(?i)[-,\\s]((?:semi|demi|extra|ultra)?bold(?:italic|oblique)?"
                    + "|black(?:italic|oblique)?|heavy(?:italic|oblique)?|italic|oblique)(?:mt)?$");

    private PdfFontNames() { }

    static Face from(PDFont font) {
        PDFontDescriptor descriptor = font == null ? null : font.getFontDescriptor();
        String postscriptName = font == null ? null : font.getName();
        String descriptorName = descriptor == null ? null : descriptor.getFontName();
        String descriptorFamily = descriptor == null ? null : descriptor.getFontFamily();
        Face embedded = embeddedFace(font);
        String family = canonicalFamily(firstNonBlank(descriptorFamily,
                embedded == null ? null : embedded.family(), postscriptName, descriptorName));
        // A family such as "Arial" deliberately excludes Bold/Italic. Keep the original
        // face names as separate evidence rather than inferring style from the chosen family.
        String styles = style(postscriptName) + style(descriptorName) + style(descriptorFamily);
        boolean bold = styles.contains("bold") || styles.contains("black") || styles.contains("heavy")
                || embedded != null && embedded.bold()
                || descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600);
        boolean italic = styles.contains("italic") || styles.contains("oblique")
                || embedded != null && embedded.italic()
                || descriptor != null && (descriptor.isItalic() || Math.abs(descriptor.getItalicAngle()) > 0.1f);
        return new Face(family, bold, italic);
    }

    private static Face embeddedFace(PDFont font) {
        // Full face names (e.g. "Liberation Serif Regular") are not families.
        // Consult only the actual embedded font, never PDFBox's installed-font
        // substitute. Reuse its already parsed metadata; do not reopen streams,
        // strip arbitrary suffixes, or copy font programs into the output.
        if (font == null || !font.isEmbedded()) return null;
        TrueTypeFont ttf = font instanceof PDTrueTypeFont simple ? simple.getTrueTypeFont()
                : font instanceof PDType0Font composite
                && composite.getDescendantFont() instanceof PDCIDFontType2 cid ? cid.getTrueTypeFont() : null;
        if (ttf == null) return null;
        try {
            var naming = ttf.getNaming();
            if (naming == null) return null;
            String subfamily = naming.getFontSubFamily();
            String styles = style("embedded-" + (subfamily == null ? ""
                    : subfamily.replaceAll("[-\\s]", "")));
            var os2 = ttf.getOS2Windows();
            boolean bold = styles.contains("bold") || styles.contains("black") || styles.contains("heavy")
                    || os2 != null && os2.getWeightClass() >= 600;
            return new Face(naming.getFontFamily(), bold,
                    styles.contains("italic") || styles.contains("oblique"));
        } catch (IOException ignored) {
            // Missing/unreadable metadata retains the existing PDF-name path.
            return null;
        }
    }

    private static String canonicalFamily(String value) {
        String name = normalize(value);
        if (name.isEmpty()) return "SimSun";
        Matcher known = commonFace(name);
        if (!known.matches()) return name;
        return switch (known.group(1).toLowerCase(Locale.ROOT)) {
            case "arial", "helvetica" -> "Arial";
            case "timesnewroman", "times" -> "Times New Roman";
            case "couriernew", "courier" -> "Courier New";
            default -> name;
        };
    }

    private static String style(String value) {
        String name = normalize(value);
        Matcher known = commonFace(name);
        if (known.matches()) return known.group(2) == null ? "" : known.group(2).toLowerCase(Locale.ROOT);
        Matcher suffix = STYLE_SUFFIX.matcher(name);
        return suffix.find() ? suffix.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static Matcher commonFace(String name) {
        return COMMON_FACE.matcher(name.replaceAll("[-,\\s]", ""));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceFirst("^[A-Z]{6}\\+", "").replace(',', ' ').strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    record Face(String family, boolean bold, boolean italic) { }
}
