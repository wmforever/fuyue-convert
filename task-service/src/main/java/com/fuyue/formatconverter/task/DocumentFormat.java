package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public enum DocumentFormat {
    OFD("ofd", "OFD", "ofd", "application/ofd",
            Set.of("application/ofd", "application/x-ofd", "application/octet-stream", "application/zip")),
    DOCX("docx", "Word DOCX", "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")),
    XLSX("xlsx", "Excel XLSX", "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")),
    CSV("csv", "CSV", "csv", "text/csv",
            Set.of("text/csv", "application/csv", "application/vnd.ms-excel", "text/plain", "application/octet-stream")),
    PDF("pdf", "PDF", "pdf", "application/pdf",
            Set.of("application/pdf", "application/octet-stream")),
    PDF_MERGED("pdf-merge", "合并后 PDF", "pdf", "application/pdf", Set.of()),
    PDF_SPLIT("pdf-split", "PDF 页面 ZIP", "zip", "application/zip", Set.of()),
    PDF_WATERMARKED("pdf-watermark", "加水印 PDF", "pdf", "application/pdf", Set.of()),
    PDF_COMPRESSED("pdf-compress", "优化后 PDF", "pdf", "application/pdf", Set.of()),
    PPTX("pptx", "PowerPoint PPTX", "pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/octet-stream")),
    PNG("png", "PNG 图片", "png", "image/png", Set.of("image/png", "application/octet-stream")),
    JPG("jpg", "JPEG 图片", "jpg", "image/jpeg", Set.of("image/jpeg", "image/jpg", "application/octet-stream")),
    HTML("html", "HTML", "html", "text/html", Set.of("text/html", "application/xhtml+xml", "application/octet-stream")),
    UOF("uof", "UOF 国产文档", "uof", "application/octet-stream", Set.of("application/octet-stream")),
    WPS("wps", "WPS 文字", "wps", "application/octet-stream", Set.of("application/octet-stream")),
    ET("et", "WPS 表格 ET", "et", "application/octet-stream", Set.of("application/octet-stream")),
    DPS("dps", "WPS 演示 DPS", "dps", "application/octet-stream", Set.of("application/octet-stream")),
    TXT("txt", "纯文本 TXT", "txt", "text/plain",
            Set.of("text/plain", "application/octet-stream"));

    private final String id;
    private final String label;
    private final String extension;
    private final String contentType;
    private final Set<String> acceptedMimeTypes;

    DocumentFormat(String id, String label, String extension, String contentType, Set<String> acceptedMimeTypes) {
        this.id = id;
        this.label = label;
        this.extension = extension;
        this.contentType = contentType;
        this.acceptedMimeTypes = acceptedMimeTypes;
    }

    @JsonValue public String id() { return id; }
    public String label() { return label; }
    public String extension() { return extension; }
    public String contentType() { return contentType; }

    public boolean acceptsFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        // This route uses LibreOffice's UOF text filter. UOS/UOP are spreadsheet and
        // presentation documents, so accepting them here would silently misroute them to DOCX.
        if (this == UOF && lower.endsWith(".uot")) return true;
        return lower.endsWith("." + extension) || (this == JPG && lower.endsWith(".jpeg"));
    }

    public boolean acceptsMimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) return true;
        String normalized = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return normalized.isEmpty() || acceptedMimeTypes.contains(normalized);
    }

    public static Optional<DocumentFormat> from(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if ("jpeg".equals(normalized)) return Optional.of(JPG);
        for (DocumentFormat format : values()) {
            if (format.id.equals(normalized) || format.name().equalsIgnoreCase(normalized) ||
                    format.extension.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    @JsonCreator
    public static DocumentFormat fromJson(String value) {
        return from(value).orElseThrow(() -> new IllegalArgumentException("Unsupported document format: " + value));
    }

    public static Optional<DocumentFormat> fromFileName(String fileName) {
        if (fileName == null) return Optional.empty();
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (format.acceptsFileName(lower)) return Optional.of(format);
        }
        return Optional.empty();
    }
}
