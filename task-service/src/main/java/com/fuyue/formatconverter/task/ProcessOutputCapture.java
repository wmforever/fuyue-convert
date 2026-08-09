package com.fuyue.formatconverter.task;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Drains process output without allowing unbounded log files or local path disclosure. */
final class ProcessOutputCapture {
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![A-Za-z0-9_.-])(?:file:)?/{1,3}[^\\s\\\"'<>]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(?<![A-Za-z0-9_.-])[A-Z]:[\\\\/][^\\s\\\"'<>]+");
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final List<String> command;
    private final int maxBytes;
    private final Thread reader;
    private volatile boolean truncated;

    private ProcessOutputCapture(Process process, List<String> command, int maxBytes) {
        this.command = List.copyOf(command);
        this.maxBytes = maxBytes;
        this.reader = new Thread(() -> drain(process.getInputStream()), "conversion-process-output");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    static ProcessOutputCapture start(Process process, List<String> command, int maxBytes) {
        return new ProcessOutputCapture(process, command, Math.max(1024, maxBytes));
    }

    String finish(Path logFile) throws IOException {
        try {
            reader.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String value;
        synchronized (captured) {
            value = captured.toString(StandardCharsets.UTF_8);
        }
        value = redact(value).replaceAll("\\s+", " ").trim();
        if (truncated) value += value.isEmpty() ? "..." : " ...";
        if (value.length() > 500) value = value.substring(0, 500) + "...";
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, value, StandardCharsets.UTF_8);
        return value;
    }

    private void drain(InputStream input) {
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                synchronized (captured) {
                    int remaining = maxBytes - captured.size();
                    if (remaining > 0) captured.write(buffer, 0, Math.min(remaining, read));
                    if (read > remaining) truncated = true;
                }
            }
        } catch (IOException ignored) {
            // A forcibly terminated process commonly closes the pipe while the reader is draining it.
        }
    }

    private String redact(String input) {
        String result = input == null ? "" : input;
        for (String argument : command) {
            if (argument == null || argument.length() < 2) continue;
            if (looksLikePath(argument)) result = result.replace(argument, "<path>");
        }
        result = WINDOWS_PATH.matcher(result).replaceAll("<path>");
        return UNIX_PATH.matcher(result).replaceAll("<path>");
    }

    private boolean looksLikePath(String value) {
        if (value.startsWith("file:/") || value.startsWith("/")) return true;
        return value.length() >= 3 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':'
                && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }
}
