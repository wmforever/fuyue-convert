package com.fuyue.formatconverter.model;

public record ConversionWarning(WarningCode code, String message, Integer pageNumber, Rect region) {
    public ConversionWarning {
        message = message == null ? code.name() : message;
    }
    public static ConversionWarning of(WarningCode code, String message, Integer pageNumber) {
        return new ConversionWarning(code, message, pageNumber, null);
    }
}

