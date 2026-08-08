package com.fuyue.formatconverter.model;

import java.util.List;

public record TextBlock(String id, int pageNumber, Rect box, String text, double baselineY,
                        FontStyle style, int zOrder, double textOffsetXmm,
                        double textOffsetYmm, List<Double> advancesMm,
                        Transform2D transform) {
    public TextBlock(String id, int pageNumber, Rect box, String text, double baselineY,
                     FontStyle style, int zOrder) {
        this(id, pageNumber, box, text, baselineY, style, zOrder,
                0, 0, List.of(), Transform2D.IDENTITY);
    }

    public TextBlock(String id, int pageNumber, Rect box, String text, double baselineY,
                     FontStyle style, int zOrder, double textOffsetXmm,
                     double textOffsetYmm, List<Double> advancesMm) {
        this(id, pageNumber, box, text, baselineY, style, zOrder,
                textOffsetXmm, textOffsetYmm, advancesMm, Transform2D.IDENTITY);
    }

    public TextBlock {
        id = id == null ? "" : id;
        text = text == null ? "" : text;
        style = style == null ? FontStyle.defaults() : style;
        textOffsetXmm = Math.max(0, textOffsetXmm);
        textOffsetYmm = Math.max(0, textOffsetYmm);
        advancesMm = advancesMm == null ? List.of() : List.copyOf(advancesMm);
        transform = transform == null ? Transform2D.IDENTITY : transform;
    }
}
