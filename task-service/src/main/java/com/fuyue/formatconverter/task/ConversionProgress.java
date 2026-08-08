package com.fuyue.formatconverter.task;

@FunctionalInterface
public interface ConversionProgress {
    void update(TaskStage stage, int progressWithinFile);
}
