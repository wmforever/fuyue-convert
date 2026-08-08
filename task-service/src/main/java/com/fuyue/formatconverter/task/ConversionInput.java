package com.fuyue.formatconverter.task;

import java.nio.file.Path;

public record ConversionInput(String displayName, String contentType, long size, Path path) {
}
