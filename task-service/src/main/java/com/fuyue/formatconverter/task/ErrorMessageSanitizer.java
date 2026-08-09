package com.fuyue.formatconverter.task;

import java.util.regex.Pattern;

/** Keeps persisted/API error details useful without disclosing local filesystem paths. */
public final class ErrorMessageSanitizer {
    private static final int MAX_MESSAGE_LENGTH = 1_000;
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![A-Za-z0-9_.-])(?:file:)?/{1,3}[^\\s\\\"'<>]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(?<![A-Za-z0-9_.-])[A-Z]:[\\\\/][^\\s\\\"'<>]+");

    private ErrorMessageSanitizer() { }

    public static String from(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return sanitize(message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    }

    public static String sanitize(String input) {
        String value = input == null ? "" : input;
        value = WINDOWS_PATH.matcher(value).replaceAll("<path>");
        value = UNIX_PATH.matcher(value).replaceAll("<path>");
        value = value.replaceAll("[\\x00-\\x1f\\x7f]+", " ").replaceAll("\\s+", " ").trim();
        if (value.length() > MAX_MESSAGE_LENGTH) value = value.substring(0, MAX_MESSAGE_LENGTH) + "...";
        return value;
    }
}
