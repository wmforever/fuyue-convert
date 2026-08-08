package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Path;

public interface FileConverter {
    ConversionRoute route();
    ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                             ParseLimits limits, ConversionProgress progress) throws Exception;
}
