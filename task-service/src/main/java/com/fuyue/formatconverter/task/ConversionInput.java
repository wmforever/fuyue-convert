package com.fuyue.formatconverter.task;

import java.nio.file.Path;

public record ConversionInput(String displayName, String contentType, long size, Path path,
                              ConversionOptions options) {
    public ConversionInput {
        options = options == null ? ConversionOptions.defaults() : options;
    }

    public ConversionInput(String displayName, String contentType, long size, Path path) {
        this(displayName, contentType, size, path, ConversionOptions.defaults());
    }
}
