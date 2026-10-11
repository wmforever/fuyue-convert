package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OoxmlPackageGuardTest {
    private static final ParseLimits LIMITS = ParseLimits.defaults();

    @Test
    void acceptsSmallSafePackage() throws Exception {
        Path packagePath = zip(List.of(
                part("[Content_Types].xml", "<Types/>"),
                part("word/document.xml", "<document/>")));

        assertDoesNotThrow(() -> OoxmlPackageGuard.requireSafe(packagePath, LIMITS));
    }

    @Test
    void rejectsTraversalAndAbsoluteEntryNames() throws Exception {
        Path traversal = zip(List.of(part("../outside.xml", "x")));
        Path absolute = zip(List.of(part("/outside.xml", "x")));

        assertThrows(IOException.class, () -> OoxmlPackageGuard.requireSafe(traversal, LIMITS));
        assertThrows(IOException.class, () -> OoxmlPackageGuard.requireSafe(absolute, LIMITS));
    }

    @Test
    void rejectsEntryCountAndExpandedSize() throws Exception {
        Path packagePath = zip(List.of(part("one.xml", "1234567890"), part("two.xml", "1234567890")));
        ParseLimits countLimit = new ParseLimits(1024 * 1024, 1024 * 1024, 1024, 1, 100, 10);
        ParseLimits sizeLimit = new ParseLimits(1024 * 1024, 12, 12, 10, 100, 10);

        assertThrows(IOException.class, () -> OoxmlPackageGuard.requireSafe(packagePath, countLimit));
        assertThrows(IOException.class, () -> OoxmlPackageGuard.requireSafe(packagePath, sizeLimit));
    }

    @Test
    void rejectsHighlyCompressedEntry() throws Exception {
        String repetitive = "a".repeat(50_000);
        Path packagePath = zip(List.of(part("word/document.xml", repetitive)));
        ParseLimits ratioLimit = new ParseLimits(1024 * 1024, 1024 * 1024,
                1024 * 1024, 10, 2, 10);

        assertThrows(IOException.class, () -> OoxmlPackageGuard.requireSafe(packagePath, ratioLimit));
    }

    private static Path zip(List<Part> parts) throws IOException {
        Path file = Files.createTempFile("ooxml-guard-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Part part : parts) {
                output.putNextEntry(new ZipEntry(part.name()));
                output.write(part.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }

    private static Part part(String name, String content) {
        return new Part(name, content);
    }

    private record Part(String name, String content) { }
}
