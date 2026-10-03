package com.fuyue.formatconverter.task;

import java.util.Locale;

public enum ImagePdfPageSize {
    ORIGINAL, A4_AUTO, A4_PORTRAIT, A4_LANDSCAPE;

    public static ImagePdfPageSize from(String value) {
        if (value == null || value.isBlank()) return ORIGINAL;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "original" -> ORIGINAL;
            case "a4-auto" -> A4_AUTO;
            case "a4-portrait" -> A4_PORTRAIT;
            case "a4-landscape" -> A4_LANDSCAPE;
            default -> throw new IllegalArgumentException("图片 PDF 纸张必须为 original、a4-auto、a4-portrait 或 a4-landscape");
        };
    }
}
