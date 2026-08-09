package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Path;
import java.time.Duration;

public record TaskServiceConfig(Path dataRoot, int concurrency, int queueCapacity, Duration timeout,
                                Duration resultTtl, long maxTaskUploadBytes, long minFreeDiskBytes,
                                ParseLimits parseLimits) {
    public TaskServiceConfig(Path dataRoot, int concurrency, int queueCapacity, Duration timeout,
                             Duration resultTtl, ParseLimits parseLimits) {
        this(dataRoot, concurrency, queueCapacity, timeout, resultTtl,
                250L * 1024 * 1024, 512L * 1024 * 1024, parseLimits);
    }

    public TaskServiceConfig {
        dataRoot = dataRoot.toAbsolutePath().normalize();
        if (concurrency < 1 || queueCapacity < 1) throw new IllegalArgumentException("Invalid executor limits");
        if (maxTaskUploadBytes < 1 || minFreeDiskBytes < 0) throw new IllegalArgumentException("Invalid storage limits");
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        resultTtl = resultTtl == null ? Duration.ofHours(24) : resultTtl;
        parseLimits = parseLimits == null ? ParseLimits.defaults() : parseLimits;
    }
}
