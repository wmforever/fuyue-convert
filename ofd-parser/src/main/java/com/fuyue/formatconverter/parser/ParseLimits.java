package com.fuyue.formatconverter.parser;

public record ParseLimits(long maxArchiveBytes, long maxExpandedBytes, long maxEntryBytes,
                          int maxEntries, double maxCompressionRatio, int maxPages) {
    public ParseLimits {
        if (maxArchiveBytes < 1 || maxExpandedBytes < 1 || maxEntryBytes < 1
                || maxEntryBytes > maxExpandedBytes || maxEntries < 1 || maxPages < 1
                || !Double.isFinite(maxCompressionRatio) || maxCompressionRatio < 1d) {
            throw new IllegalArgumentException("Invalid document parse limits");
        }
    }

    public static ParseLimits defaults() {
        return new ParseLimits(50L * 1024 * 1024, 200L * 1024 * 1024,
                40L * 1024 * 1024, 10_000, 100d, 500);
    }
}
