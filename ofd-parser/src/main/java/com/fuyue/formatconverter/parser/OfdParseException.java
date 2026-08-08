package com.fuyue.formatconverter.parser;

public class OfdParseException extends Exception {
    private final String code;

    public OfdParseException(String code, String message) { super(message); this.code = code; }
    public OfdParseException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}

