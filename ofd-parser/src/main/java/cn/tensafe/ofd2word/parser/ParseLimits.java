package cn.tensafe.ofd2word.parser;

public record ParseLimits(long maxArchiveBytes, long maxExpandedBytes, long maxEntryBytes,
                          int maxEntries, double maxCompressionRatio, int maxPages) {
    public static ParseLimits defaults() {
        return new ParseLimits(50L * 1024 * 1024, 200L * 1024 * 1024,
                40L * 1024 * 1024, 10_000, 100d, 500);
    }
}

