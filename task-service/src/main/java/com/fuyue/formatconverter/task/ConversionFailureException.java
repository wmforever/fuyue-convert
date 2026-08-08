package com.fuyue.formatconverter.task;

import java.io.IOException;

public final class ConversionFailureException extends IOException {
    private final String code;

    public ConversionFailureException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "CONVERSION_FAILED" : code;
    }

    public String code() { return code; }
}
