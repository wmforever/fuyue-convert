package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;

import java.time.Instant;
import java.util.List;

public record TaskSnapshot(String taskId, TaskStatus status, TaskStage stage, int progress,
                           String errorCode, String errorMessage, List<ConversionWarning> warnings,
                           List<TaskFileResult> files, boolean downloadReady, String downloadName,
                           DocumentFormat sourceFormat, DocumentFormat targetFormat,
                           Instant createdAt, Instant updatedAt, Instant expiresAt) {
    public TaskSnapshot {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        files = files == null ? List.of() : List.copyOf(files);
        sourceFormat = sourceFormat == null ? DocumentFormat.OFD : sourceFormat;
        targetFormat = targetFormat == null ? DocumentFormat.DOCX : targetFormat;
    }
}
