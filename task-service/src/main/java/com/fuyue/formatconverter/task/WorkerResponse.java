package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;

import java.util.List;

public record WorkerResponse(boolean success, String outputPath, String outputName, Integer pageCount,
                             List<ConversionWarning> warnings, String errorCode, String errorMessage) {
    public WorkerResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    static WorkerResponse success(ConversionOutput output) {
        return new WorkerResponse(true, output.path().toAbsolutePath().normalize().toString(), output.outputName(),
                output.pageCount(), output.warnings(), null, null);
    }

    static WorkerResponse failure(String code, String message) {
        return new WorkerResponse(false, null, null, null, List.of(), code, message);
    }
}
