package com.fuyue.formatconverter.task;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a task result alive while an HTTP response is streaming it.
 */
public final class DownloadLease implements AutoCloseable {
    private final DownloadArtifact artifact;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DownloadLease(DownloadArtifact artifact, Runnable release) {
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.release = Objects.requireNonNull(release, "release");
    }

    public DownloadArtifact artifact() { return artifact; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }
}
