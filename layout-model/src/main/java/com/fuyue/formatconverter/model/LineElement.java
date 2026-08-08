package com.fuyue.formatconverter.model;

public record LineElement(String id, int pageNumber, Point start, Point end, double widthMm,
                          ColorValue color, int zOrder) {
    public LineElement {
        id = id == null ? "" : id;
        widthMm = widthMm > 0 ? widthMm : 0.2d;
        color = color == null ? ColorValue.BLACK : color;
    }

    public boolean horizontal(double tolerance) { return Math.abs(start.y() - end.y()) <= tolerance; }
    public boolean vertical(double tolerance) { return Math.abs(start.x() - end.x()) <= tolerance; }
    public double minX() { return Math.min(start.x(), end.x()); }
    public double maxX() { return Math.max(start.x(), end.x()); }
    public double minY() { return Math.min(start.y(), end.y()); }
    public double maxY() { return Math.max(start.y(), end.y()); }
}

