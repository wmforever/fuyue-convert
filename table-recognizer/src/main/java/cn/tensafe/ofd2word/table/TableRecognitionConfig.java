package cn.tensafe.ofd2word.table;

public record TableRecognitionConfig(double axisToleranceMm, double gapToleranceMm,
                                     double coverageThreshold, double minimumConfidence) {
    public static TableRecognitionConfig defaults() { return new TableRecognitionConfig(0.25, 0.6, 0.85, 0.65); }
}

