package com.fuyue.formatconverter.task;

import java.io.IOException;

/** Structured failure from an isolated native process. */
final class ExternalProcessException extends IOException {
    enum Reason { START_FAILED, TIMEOUT, NON_ZERO_EXIT }

    private final Reason reason;
    private final Integer exitCode;

    ExternalProcessException(Reason reason, Integer exitCode, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.exitCode = exitCode;
    }

    Reason reason() { return reason; }
    Integer exitCode() { return exitCode; }
}
