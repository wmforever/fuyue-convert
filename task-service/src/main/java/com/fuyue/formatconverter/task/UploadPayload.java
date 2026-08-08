package com.fuyue.formatconverter.task;

import java.io.IOException;
import java.io.InputStream;

public record UploadPayload(String originalName, String contentType, long size, InputStreamSupplier source) {
    public UploadPayload(String originalName, long size, InputStreamSupplier source) {
        this(originalName, "application/octet-stream", size, source);
    }
    @FunctionalInterface public interface InputStreamSupplier { InputStream open() throws IOException; }
}
