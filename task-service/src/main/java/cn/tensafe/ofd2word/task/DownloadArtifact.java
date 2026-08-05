package cn.tensafe.ofd2word.task;

import java.nio.file.Path;

public record DownloadArtifact(Path path, String fileName, String contentType) {
}

