package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

public record WorkerRequest(String sourceFormat, String targetFormat, String displayName, String contentType,
                            long size, String inputPath, String workPath, String outputPath,
                            ParseLimits limits, String officeBinary, long officeTimeoutMillis) {
}
