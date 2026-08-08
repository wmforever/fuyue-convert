package com.fuyue.formatconverter.model;

public record FontStyle(String family, double sizePt, boolean bold, boolean italic, ColorValue color) {
    public FontStyle {
        family = family == null || family.isBlank() ? "SimSun" : family;
        sizePt = sizePt > 0 ? sizePt : 10.5d;
        color = color == null ? ColorValue.BLACK : color;
    }

    public static FontStyle defaults() { return new FontStyle("SimSun", 10.5, false, false, ColorValue.BLACK); }
}

