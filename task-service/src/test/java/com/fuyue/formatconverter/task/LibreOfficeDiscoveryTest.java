package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LibreOfficeDiscoveryTest {
    @Test void includesWindowsExecutableNamesAndStandardInstallLocations() {
        var candidates = LibreOfficeConverter.discoveryCandidates("", "/opt/libreoffice", true,
                "C:\\Program Files", "C:\\Program Files (x86)", "C:\\Users\\Tester\\AppData\\Local");

        assertTrue(candidates.contains(Path.of("/opt/libreoffice", "soffice.exe")));
        assertTrue(candidates.contains(Path.of("C:\\Program Files", "LibreOffice", "program", "soffice.exe")));
        assertTrue(candidates.contains(Path.of("C:\\Program Files (x86)", "LibreOffice", "program", "soffice.exe")));
        assertTrue(candidates.contains(Path.of("C:\\Users\\Tester\\AppData\\Local", "Programs", "LibreOffice",
                "program", "soffice.exe")));
    }
}
