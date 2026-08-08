package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;

import java.nio.file.Path;
import java.util.List;

public record ConversionOutput(Path path, String outputName, Integer pageCount, List<ConversionWarning> warnings) {
    public ConversionOutput {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
