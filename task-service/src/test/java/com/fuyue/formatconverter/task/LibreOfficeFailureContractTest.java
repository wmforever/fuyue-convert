package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class LibreOfficeFailureContractTest {
    @TempDir Path temp;

    @Test
    void rejectsMalformedOfficePackageWithStableClientErrorBeforeStartingProcess() throws Exception {
        Path malformed = temp.resolve("malformed.pptx");
        writeZip(malformed, "[Content_Types].xml");
        LibreOfficeConverter converter = converter(temp.resolve("missing-soffice"));

        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> converter.convert(input(malformed), temp.resolve("malformed-work"), temp.resolve("out.pdf"),
                        ParseLimits.defaults(), (stage, progress) -> { }));

        assertEquals("INVALID_OFFICE_DOCUMENT", error.code());
        assertTrue(error.getMessage().contains("文件结构无效"));
        assertFalse(error.getMessage().contains("Office package"));
    }

    @Test
    void hidesLibreOfficeUnreadableInputLogBehindStableErrorCode() throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path source = temp.resolve("unreadable.pptx");
        writeZip(source, "[Content_Types].xml", "ppt/presentation.xml");
        Path fakeOffice = temp.resolve("fake-soffice");
        Files.writeString(fakeOffice, "#!/bin/sh\n"
                + "echo 'Error: source file could not be loaded /private/very-long-sensitive-path'\n"
                + "exit 1\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fakeOffice, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));

        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> converter(fakeOffice).convert(input(source), temp.resolve("process-work"),
                        temp.resolve("process-out.pdf"), ParseLimits.defaults(), (stage, progress) -> { }));

        assertEquals("INVALID_OFFICE_DOCUMENT", error.code());
        assertTrue(error.getMessage().contains("文件结构无效"));
        assertFalse(error.getMessage().contains("source file could not be loaded"));
        assertFalse(error.getMessage().contains("sensitive-path"));
    }

    private LibreOfficeConverter converter(Path binary) {
        return new LibreOfficeConverter(DocumentFormat.PPTX, DocumentFormat.PDF, binary,
                Duration.ofSeconds(5), "test converter");
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), DocumentFormat.PPTX.contentType(),
                Files.size(source), source);
    }

    private void writeZip(Path path, String... entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("<xml/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
