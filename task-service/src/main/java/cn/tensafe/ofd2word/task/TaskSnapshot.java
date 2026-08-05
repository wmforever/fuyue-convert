package cn.tensafe.ofd2word.task;

import cn.tensafe.ofd2word.model.ConversionWarning;

import java.time.Instant;
import java.util.List;

public record TaskSnapshot(String taskId, TaskStatus status, TaskStage stage, int progress,
                           String errorCode, String errorMessage, List<ConversionWarning> warnings,
                           List<TaskFileResult> files, boolean downloadReady, String downloadName,
                           Instant createdAt, Instant updatedAt, Instant expiresAt) {
    public TaskSnapshot {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        files = files == null ? List.of() : List.copyOf(files);
    }
}

