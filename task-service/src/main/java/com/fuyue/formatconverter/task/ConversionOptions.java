package com.fuyue.formatconverter.task;

import java.util.Locale;
import java.util.regex.Pattern;

public record ConversionOptions(PdfCompressionMode compressionMode,
                                String watermarkText,
                                Double watermarkOpacity,
                                Double watermarkAngle,
                                WatermarkPosition watermarkPosition,
                                Boolean watermarkTiled,
                                String watermarkPages,
                                String watermarkColor) {
    private static final Pattern PAGE_RANGE = Pattern.compile("(?i)all|(?:[1-9]\\d*(?:-[1-9]\\d*)?)(?:,(?:[1-9]\\d*(?:-[1-9]\\d*)?))*");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    public ConversionOptions {
        compressionMode = compressionMode == null ? PdfCompressionMode.LOSSLESS : compressionMode;
        watermarkText = watermarkText == null || watermarkText.isBlank() ? "CONFIDENTIAL" : watermarkText.strip();
        watermarkOpacity = watermarkOpacity == null ? 0.18d : watermarkOpacity;
        watermarkAngle = watermarkAngle == null ? 35d : watermarkAngle;
        watermarkPosition = watermarkPosition == null ? WatermarkPosition.CENTER : watermarkPosition;
        watermarkTiled = watermarkTiled != null && watermarkTiled;
        watermarkPages = watermarkPages == null || watermarkPages.isBlank()
                ? "all" : watermarkPages.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        watermarkColor = watermarkColor == null || watermarkColor.isBlank()
                ? "#969696" : watermarkColor.strip().toUpperCase(Locale.ROOT);
        if (watermarkText.codePointCount(0, watermarkText.length()) > 80) {
            throw new IllegalArgumentException("水印文字不能超过 80 个字符");
        }
        if (watermarkText.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("水印文字不能包含控制字符");
        }
        if (!Double.isFinite(watermarkOpacity) || watermarkOpacity < 0.05d || watermarkOpacity > 0.85d) {
            throw new IllegalArgumentException("水印透明度必须在 0.05 到 0.85 之间");
        }
        if (!Double.isFinite(watermarkAngle) || watermarkAngle < -180d || watermarkAngle > 180d) {
            throw new IllegalArgumentException("水印角度必须在 -180 到 180 度之间");
        }
        if (!PAGE_RANGE.matcher(watermarkPages).matches()) {
            throw new IllegalArgumentException("水印页码范围格式无效，例如 all、1、1-3、1,3-5");
        }
        if (!HEX_COLOR.matcher(watermarkColor).matches()) {
            throw new IllegalArgumentException("水印颜色必须是 #RRGGBB 格式");
        }
    }

    public static ConversionOptions defaults() {
        return new ConversionOptions(null, null, null, null, null, null, null, null);
    }

    public static ConversionOptions fromRequest(String compressionMode, String watermarkText,
                                                Double watermarkOpacity, Double watermarkAngle,
                                                String watermarkPosition, Boolean watermarkTiled,
                                                String watermarkPages, String watermarkColor) {
        return new ConversionOptions(PdfCompressionMode.from(compressionMode), watermarkText,
                watermarkOpacity, watermarkAngle, WatermarkPosition.from(watermarkPosition),
                watermarkTiled, watermarkPages, watermarkColor);
    }
}
