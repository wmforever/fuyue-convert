package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Path;
import java.time.Duration;

public record TaskServiceConfig(Path dataRoot, int concurrency, int queueCapacity, Duration timeout,
                                Duration resultTtl, int maxFilesPerTask, long maxTaskUploadBytes,
                                long maxTaskOutputBytes, long minFreeDiskBytes,
                                ParseLimits parseLimits) {
    public TaskServiceConfig(Path dataRoot, int concurrency, int queueCapacity, Duration timeout,
                             Duration resultTtl, ParseLimits parseLimits) {
        this(dataRoot, concurrency, queueCapacity, timeout, resultTtl, 100,
                250L * 1024 * 1024, 512L * 1024 * 1024, 512L * 1024 * 1024, parseLimits);
    }

    public TaskServiceConfig(Path dataRoot, int concurrency, int queueCapacity, Duration timeout,
                             Duration resultTtl, long maxTaskUploadBytes, long minFreeDiskBytes,
                             ParseLimits parseLimits) {
        this(dataRoot, concurrency, queueCapacity, timeout, resultTtl, 100,
                maxTaskUploadBytes, 512L * 1024 * 1024, minFreeDiskBytes, parseLimits);
    }

    public TaskServiceConfig {
        if (dataRoot == null) throw new IllegalArgumentException("dataRoot 不能为空");
        dataRoot = dataRoot.toAbsolutePath().normalize();
        if (concurrency < 1 || queueCapacity < 1) throw new IllegalArgumentException("Invalid executor limits");
        if (maxFilesPerTask < 1 || maxTaskUploadBytes < 1 || maxTaskOutputBytes < 1 || minFreeDiskBytes < 0) {
            throw new IllegalArgumentException("Invalid storage limits");
        }
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        resultTtl = resultTtl == null ? Duration.ofHours(24) : resultTtl;
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout 必须大于 0");
        if (resultTtl.isZero() || resultTtl.isNegative()) throw new IllegalArgumentException("resultTtl 必须大于 0");
        parseLimits = parseLimits == null ? ParseLimits.defaults() : parseLimits;
    }
}
