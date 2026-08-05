package cn.tensafe.ofd2word.parser;

import java.nio.file.Path;

public record SafeOfdPackage(Path root, long expandedBytes, int entryCount) {
    public SafeOfdPackage {
        root = root.toAbsolutePath().normalize();
    }
}

