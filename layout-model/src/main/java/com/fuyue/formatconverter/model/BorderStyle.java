package com.fuyue.formatconverter.model;

public record BorderStyle(double widthMm, ColorValue color, Pattern pattern) {
    public enum Pattern { NONE, SOLID, DASHED, DOTTED, DOUBLE }
    public static final BorderStyle NONE = new BorderStyle(0, ColorValue.BLACK, Pattern.NONE);
    public static BorderStyle solid(double widthMm, ColorValue color) {
        return new BorderStyle(widthMm, color, Pattern.SOLID);
    }
}

