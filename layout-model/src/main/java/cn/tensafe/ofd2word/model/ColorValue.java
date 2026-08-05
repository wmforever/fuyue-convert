package cn.tensafe.ofd2word.model;

public record ColorValue(int red, int green, int blue, int alpha) {
    public static final ColorValue BLACK = new ColorValue(0, 0, 0, 255);
    public static final ColorValue WHITE = new ColorValue(255, 255, 255, 255);

    public ColorValue {
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255
                || alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("Color channel out of range");
        }
    }

    public String rgbHex() { return "%02X%02X%02X".formatted(red, green, blue); }
}

