package com.fuyue.formatconverter.parser;

import java.nio.file.Path;

public record SafeOfdPackage(Path root, long expandedBytes, int entryCount) {
    public SafeOfdPackage {
        root = root.toAbsolutePath().normalize();
    }
}

