package com.fuyue.formatconverter.task;

import java.util.Locale;

public enum WatermarkPosition {
    CENTER("center"),
    TOP_LEFT("top-left"),
    TOP_RIGHT("top-right"),
    BOTTOM_LEFT("bottom-left"),
    BOTTOM_RIGHT("bottom-right");

    private final String id;

    WatermarkPosition(String id) { this.id = id; }

    public String id() { return id; }

    public static WatermarkPosition from(String value) {
        if (value == null || value.isBlank()) return CENTER;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (WatermarkPosition position : values()) if (position.id.equals(normalized)) return position;
        throw new IllegalArgumentException("不支持的水印位置：" + value);
    }
}
