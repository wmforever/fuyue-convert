package cn.tensafe.ofd2word.task;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(String taskId) { super("任务不存在：" + taskId); }
}

