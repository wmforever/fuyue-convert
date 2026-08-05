package cn.tensafe.ofd2word.task;

import cn.tensafe.ofd2word.parser.ParseLimits;

import java.nio.file.Path;
import java.time.Duration;

public record TaskServiceConfig(Path dataRoot, int concurrency, int queueCapacity, Duration timeout,
                                Duration resultTtl, ParseLimits parseLimits) {
    public TaskServiceConfig {
        dataRoot = dataRoot.toAbsolutePath().normalize();
        if (concurrency < 1 || queueCapacity < 1) throw new IllegalArgumentException("Invalid executor limits");
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        resultTtl = resultTtl == null ? Duration.ofHours(24) : resultTtl;
        parseLimits = parseLimits == null ? ParseLimits.defaults() : parseLimits;
    }
}

