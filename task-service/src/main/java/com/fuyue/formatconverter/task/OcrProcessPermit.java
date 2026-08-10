package com.fuyue.formatconverter.task;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/** Cross-JVM OCR concurrency permit, shared by forked conversion workers on the same host. */
final class OcrProcessPermit implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private OcrProcessPermit(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static OcrProcessPermit acquire(Path directory, int slots, Duration timeout) throws Exception {
        Files.createDirectories(directory);
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            for (int slot = 0; slot < slots; slot++) {
                Path path = directory.resolve("slot-%02d.lock".formatted(slot));
                FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) return new OcrProcessPermit(channel, lock);
                } catch (OverlappingFileLockException ignored) {
                    // Another OCR in this JVM owns the slot.
                }
                channel.close();
            }
            Thread.sleep(50L);
        } while (System.nanoTime() < deadline);
        throw new ConversionFailureException("OCR_CAPACITY_EXCEEDED", "OCR 并发已达到上限，等待可用进程超时");
    }

    @Override
    public void close() throws java.io.IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
