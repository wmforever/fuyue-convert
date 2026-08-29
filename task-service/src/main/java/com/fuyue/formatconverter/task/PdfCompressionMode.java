package com.fuyue.formatconverter.task;

import java.util.Locale;

public enum PdfCompressionMode {
    LOSSLESS("lossless"),
    BALANCED("balanced"),
    STRONG("strong");

    private final String id;

    PdfCompressionMode(String id) { this.id = id; }

    public String id() { return id; }

    public static PdfCompressionMode from(String value) {
        if (value == null || value.isBlank()) return LOSSLESS;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (PdfCompressionMode mode : values()) if (mode.id.equals(normalized)) return mode;
        throw new IllegalArgumentException("不支持的 PDF 压缩等级：" + value);
    }
}
