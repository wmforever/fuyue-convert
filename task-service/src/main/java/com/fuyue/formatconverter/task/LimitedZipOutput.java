package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams generated files into a ZIP while enforcing the uncompressed entry
 * and archive limits.  A failed or incomplete archive is removed on close.
 */
final class LimitedZipOutput implements AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path archive;
    private final ParseLimits limits;
    private final String label;
    private final ZipOutputStream zip;
    private long expandedBytes;
    private boolean closed;
    private boolean finished;

    private LimitedZipOutput(Path archive, ParseLimits limits, String label) throws IOException {
        this.archive = archive.toAbsolutePath().normalize();
        this.limits = limits;
        this.label = label == null || label.isBlank() ? "ZIP 输出" : label;
        Path parent = this.archive.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(this.archive)));
    }

    static LimitedZipOutput open(Path archive, ParseLimits limits, String label) throws IOException {
        if (archive == null) throw new IllegalArgumentException("archive");
        if (limits == null) throw new IllegalArgumentException("limits");
        return new LimitedZipOutput(archive, limits, label);
    }

    /** Adds one regular file, counting its uncompressed bytes while copying. */
    void addFile(String entryName, Path source) throws IOException {
        if (entryName == null || entryName.isBlank()) throw new IOException(label + "条目名称为空");
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException(label + "源文件不存在：" + source);
        }
        zip.putNextEntry(new ZipEntry(entryName));
        boolean copied = false;
        long entryBytes = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                entryBytes = checkedEntryBytes(entryBytes, read);
                checkedExpandedBytes(read, entryBytes);
                zip.write(buffer, 0, read);
            }
            copied = true;
        } finally {
            // An incomplete entry is deliberately left unfinished; close() then
            // deletes the partial archive instead of trying to publish it.
            if (copied) zip.closeEntry();
        }
    }

    /** Adds an in-memory metadata entry using the same uncompressed limits. */
    void addBytes(String entryName, byte[] bytes) throws IOException {
        if (entryName == null || entryName.isBlank()) throw new IOException(label + "条目名称为空");
        if (bytes == null) throw new IOException(label + "条目内容为空：" + entryName);
        zip.putNextEntry(new ZipEntry(entryName));
        boolean copied = false;
        long entryBytes = 0;
        try {
            for (int offset = 0; offset < bytes.length;) {
                int length = Math.min(BUFFER_SIZE, bytes.length - offset);
                entryBytes = checkedEntryBytes(entryBytes, length);
                checkedExpandedBytes(length, entryBytes);
                zip.write(bytes, offset, length);
                offset += length;
            }
            copied = true;
        } finally {
            if (copied) zip.closeEntry();
        }
    }

    long expandedBytes() { return expandedBytes; }

    /** Finishes the central directory and marks the archive publishable. */
    void finish() throws IOException {
        if (closed) return;
        try {
            zip.close();
            finished = true;
        } finally {
            closed = true;
            if (!finished) deleteArchive();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        try {
            zip.close();
        } catch (IOException e) {
            failure = e;
        } finally {
            closed = true;
            if (!finished) {
                try {
                    deleteArchive();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private long checkedEntryBytes(long current, long additional) throws ConversionFailureException {
        if (additional > limits.maxEntryBytes() - current) {
            long attempted = safeAdd(current, additional);
            throw new ConversionFailureException("OUTPUT_LIMIT_EXCEEDED",
                    label + "单个 ZIP 条目超过限制：" + attempted + " > " + limits.maxEntryBytes());
        }
        return current + additional;
    }

    private void checkedExpandedBytes(long additional, long entryBytes) throws ConversionFailureException {
        if (additional > limits.maxExpandedBytes() - expandedBytes) {
            long attempted = safeAdd(expandedBytes, additional);
            throw new ConversionFailureException("OUTPUT_LIMIT_EXCEEDED",
                    label + "总展开大小超过限制：" + attempted + " > " + limits.maxExpandedBytes()
                            + "（当前条目 " + entryBytes + " 字节）");
        }
        expandedBytes += additional;
    }

    private long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }

    private void deleteArchive() throws IOException {
        Files.deleteIfExists(archive);
    }
}
