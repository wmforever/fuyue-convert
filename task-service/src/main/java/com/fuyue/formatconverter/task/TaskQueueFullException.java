package com.fuyue.formatconverter.task;

public class TaskQueueFullException extends RuntimeException {
    public TaskQueueFullException() { super("转换队列已满，请稍后重试"); }
}

