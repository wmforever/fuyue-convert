package com.fuyue.formatconverter.task;

import java.nio.file.Path;

public record DownloadArtifact(Path path, String fileName, String contentType) {
}

