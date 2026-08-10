package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OcrProcessPermitTest {
    @TempDir Path temp;

    @Test
    void enforcesSharedSlotAndReleasesIt() throws Exception {
        try (OcrProcessPermit ignored = OcrProcessPermit.acquire(temp, 1, Duration.ofSeconds(1))) {
            ConversionFailureException failure = assertThrows(ConversionFailureException.class,
                    () -> OcrProcessPermit.acquire(temp, 1, Duration.ofMillis(100)));
            assertEquals("OCR_CAPACITY_EXCEEDED", failure.code());
        }
        try (OcrProcessPermit ignored = OcrProcessPermit.acquire(temp, 1, Duration.ofSeconds(1))) {
            // Reacquisition proves that normal completion releases the cross-process slot.
        }
    }
}
