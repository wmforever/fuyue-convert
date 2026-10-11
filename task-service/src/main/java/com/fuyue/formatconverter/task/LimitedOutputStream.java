package com.fuyue.formatconverter.task;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Counts bytes written to a generated file and fails before the configured
 * output limit is crossed.  This is used while a converter is serializing a
 * page, before the page is copied into a ZIP entry.
 */
final class LimitedOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final long maxBytes;
    private final String label;
    private long written;

    LimitedOutputStream(OutputStream delegate, long maxBytes, String label) {
        if (delegate == null) throw new IllegalArgumentException("delegate");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes");
        this.delegate = delegate;
        this.maxBytes = maxBytes;
        this.label = label == null || label.isBlank() ? "输出" : label;
    }

    @Override
    public void write(int value) throws IOException {
        ensureWithinLimit(1);
        delegate.write(value);
        written++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return;
        ensureWithinLimit(length);
        delegate.write(bytes, offset, length);
        written += length;
    }

    @Override
    public void flush() throws IOException { delegate.flush(); }

    @Override
    public void close() throws IOException { delegate.close(); }

    long written() { return written; }

    private void ensureWithinLimit(long additional) throws ConversionFailureException {
        if (additional > maxBytes - written) {
            long attempted;
            try { attempted = Math.addExact(written, additional); }
            catch (ArithmeticException e) { attempted = Long.MAX_VALUE; }
            throw new ConversionFailureException("OUTPUT_LIMIT_EXCEEDED",
                    label + "超过单文件输出限制：" + attempted + " > " + maxBytes);
        }
    }
}
