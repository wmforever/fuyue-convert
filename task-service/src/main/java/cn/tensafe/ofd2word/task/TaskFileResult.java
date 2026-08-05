package cn.tensafe.ofd2word.task;

public record TaskFileResult(String fileName, boolean success, String outputName, Integer pageCount,
                             String errorCode, String errorMessage) {
}
