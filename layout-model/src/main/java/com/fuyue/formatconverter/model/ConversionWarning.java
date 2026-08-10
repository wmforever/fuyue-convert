package com.fuyue.formatconverter.model;

public record ConversionWarning(WarningCode code, String message, Integer pageNumber, Rect region,
                                Double confidence) {
    public ConversionWarning {
        message = message == null ? code.name() : message;
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0d || confidence > 1d)) {
            throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
        }
    }
    public ConversionWarning(WarningCode code, String message, Integer pageNumber, Rect region) {
        this(code, message, pageNumber, region, null);
    }
    public static ConversionWarning of(WarningCode code, String message, Integer pageNumber) {
        return new ConversionWarning(code, message, pageNumber, null, null);
    }
    public static ConversionWarning withConfidence(WarningCode code, String message, Integer pageNumber,
                                                   Rect region, double confidence) {
        return new ConversionWarning(code, message, pageNumber, region, confidence);
    }
}
