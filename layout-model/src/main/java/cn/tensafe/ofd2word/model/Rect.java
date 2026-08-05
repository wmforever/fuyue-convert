package cn.tensafe.ofd2word.model;

public record Rect(double x, double y, double width, double height) {
    public Rect {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Rectangle values must be finite");
        }
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Rectangle size cannot be negative");
        }
    }

    public double right() { return x + width; }
    public double bottom() { return y + height; }
    public Point center() { return new Point(x + width / 2d, y + height / 2d); }

    public boolean contains(Point point, double tolerance) {
        return point.x() >= x - tolerance && point.x() <= right() + tolerance
                && point.y() >= y - tolerance && point.y() <= bottom() + tolerance;
    }

    public double intersectionArea(Rect other) {
        double w = Math.max(0, Math.min(right(), other.right()) - Math.max(x, other.x));
        double h = Math.max(0, Math.min(bottom(), other.bottom()) - Math.max(y, other.y));
        return w * h;
    }

    public Rect union(Rect other) {
        double left = Math.min(x, other.x);
        double top = Math.min(y, other.y);
        double right = Math.max(right(), other.right());
        double bottom = Math.max(bottom(), other.bottom());
        return new Rect(left, top, right - left, bottom - top);
    }
}

