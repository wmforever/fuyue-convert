package com.fuyue.formatconverter.task;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;

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
        String family = canonicalFamily(firstNonBlank(descriptorFamily, postscriptName, descriptorName));
        // A family such as "Arial" deliberately excludes Bold/Italic. Keep the original
        // face names as separate evidence rather than inferring style from the chosen family.
        String styles = style(postscriptName) + style(descriptorName) + style(descriptorFamily);
        boolean bold = styles.contains("bold") || styles.contains("black") || styles.contains("heavy")
                || descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600);
        boolean italic = styles.contains("italic") || styles.contains("oblique")
                || descriptor != null && (descriptor.isItalic() || Math.abs(descriptor.getItalicAngle()) > 0.1f);
        return new Face(family, bold, italic);
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
