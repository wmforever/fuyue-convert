package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitedZipOutputTest {
    @TempDir Path temp;

    @Test
    void streamsEntriesAndCountsTheirUncompressedBytes() throws Exception {
        Path first = Files.writeString(temp.resolve("first.txt"), "12345678");
        Path second = Files.writeString(temp.resolve("second.txt"), "abcdefgh");
        Path archive = temp.resolve("pages.zip");
        ParseLimits limits = new ParseLimits(1024, 16, 8, 10, 100, 10);

        try (LimitedZipOutput output = LimitedZipOutput.open(archive, limits, "测试 ZIP")) {
            output.addFile("first.txt", first);
            output.addFile("second.txt", second);
            assertEquals(16, output.expandedBytes());
            output.finish();
        }

        assertTrue(Files.isRegularFile(archive));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals(List.of("first.txt", "second.txt"),
                    zip.stream().map(entry -> entry.getName()).toList());
        }
    }

    @Test
    void rejectsEntryLimitAndRemovesPartialArchive() throws Exception {
        Path source = Files.writeString(temp.resolve("large.txt"), "123456789");
        Path archive = temp.resolve("too-large.zip");
        ParseLimits limits = new ParseLimits(1024, 20, 8, 10, 100, 10);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class, () -> {
            try (LimitedZipOutput output = LimitedZipOutput.open(archive, limits, "测试 ZIP")) {
                output.addFile("large.txt", source);
                output.finish();
            }
        });

        assertEquals("OUTPUT_LIMIT_EXCEEDED", failure.code());
        assertFalse(Files.exists(archive));
    }

    @Test
    void rejectsExpandedLimitAfterACompletedEntryAndRemovesPartialArchive() throws Exception {
        Path first = Files.writeString(temp.resolve("first.txt"), "12345678");
        Path second = Files.writeString(temp.resolve("second.txt"), "abcdefgh");
        Path archive = temp.resolve("expanded-too-large.zip");
        ParseLimits limits = new ParseLimits(1024, 10, 10, 10, 100, 10);

        ConversionFailureException failure = assertThrows(ConversionFailureException.class, () -> {
            try (LimitedZipOutput output = LimitedZipOutput.open(archive, limits, "测试 ZIP")) {
                output.addFile("first.txt", first);
                output.addFile("second.txt", second);
                output.finish();
            }
        });

        assertEquals("OUTPUT_LIMIT_EXCEEDED", failure.code());
        assertFalse(Files.exists(archive));
    }
}
