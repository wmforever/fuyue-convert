package com.fuyue.formatconverter.task;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(String taskId) { super("任务不存在：" + taskId); }
}

