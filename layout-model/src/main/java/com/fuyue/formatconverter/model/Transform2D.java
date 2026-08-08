package com.fuyue.formatconverter.model;

/**
 * OFD affine matrix in the standard {@code [a b c d e f]} form.
 * Keeping the matrix in the neutral layout model lets renderers preserve
 * horizontal scaling and rotation without depending on OFDRW classes.
 */
public record Transform2D(double a, double b, double c, double d, double e, double f) {
    public static final Transform2D IDENTITY = new Transform2D(1, 0, 0, 1, 0, 0);

    public Transform2D {
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c)
                || !Double.isFinite(d) || !Double.isFinite(e) || !Double.isFinite(f)) {
            throw new IllegalArgumentException("Transform values must be finite");
        }
    }

    public Point apply(Point point) {
        return new Point(a * point.x() + c * point.y() + e,
                b * point.x() + d * point.y() + f);
    }

    public Point applyVector(Point vector) {
        return new Point(a * vector.x() + c * vector.y(),
                b * vector.x() + d * vector.y());
    }

    public double scaleX() { return Math.hypot(a, b); }
    public double scaleY() { return Math.hypot(c, d); }
    public double rotationDegrees() { return Math.toDegrees(Math.atan2(b, a)); }

    public boolean hasSkew(double tolerance) {
        Point x = applyVector(new Point(1, 0));
        Point y = applyVector(new Point(0, 1));
        double denominator = Math.max(1e-9, scaleX() * scaleY());
        return Math.abs((x.x() * y.x() + x.y() * y.y()) / denominator) > tolerance;
    }
}
