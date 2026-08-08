package com.fuyue.formatconverter.task;

public record TaskFileResult(String fileName, boolean success, String outputName, Integer pageCount,
                             String errorCode, String errorMessage,
                             DocumentFormat sourceFormat, DocumentFormat targetFormat) {
    public TaskFileResult {
        sourceFormat = sourceFormat == null ? DocumentFormat.OFD : sourceFormat;
        targetFormat = targetFormat == null ? DocumentFormat.DOCX : targetFormat;
    }
}
