package com.fuyue.formatconverter.task;

public record WorkerProgress(TaskStage stage, int progress) {
    public WorkerProgress {
        progress = Math.max(0, Math.min(100, progress));
    }
}
