package com.fuyue.formatconverter.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SafeOfdExtractorTest {
    @TempDir Path temp;

    @Test void extractsMinimalSafeContainer() throws Exception {
        Path archive = zip("safe.ofd", "OFD.xml", "<ofd:OFD xmlns:ofd=\"http://www.ofdspec.org/2016\"/>");
        SafeOfdPackage result = new SafeOfdExtractor().extract(archive, temp.resolve("out"), ParseLimits.defaults());
        assertTrue(Files.isRegularFile(result.root().resolve("OFD.xml")));
        assertEquals(1, result.entryCount());
    }

    @Test void rejectsZipSlip() throws Exception {
        Path archive = zip("bad.ofd", "../outside.txt", "bad");
        OfdParseException error = assertThrows(OfdParseException.class,
                () -> new SafeOfdExtractor().extract(archive, temp.resolve("out"), ParseLimits.defaults()));
        assertEquals("OFD_ZIP_SLIP", error.code());
        assertFalse(Files.exists(temp.resolve("outside.txt")));
    }

    @Test void rejectsDoctype() throws Exception {
        Path archive = zip("xxe.ofd", "OFD.xml", "<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>");
        OfdParseException error = assertThrows(OfdParseException.class,
                () -> new SafeOfdExtractor().extract(archive, temp.resolve("out"), ParseLimits.defaults()));
        assertEquals("OFD_UNSAFE_XML", error.code());
    }

    private Path zip(String file, String entry, String value) throws IOException {
        Path path = temp.resolve(file);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }
}
