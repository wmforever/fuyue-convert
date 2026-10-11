package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Performs a bounded, structural preflight before handing an OOXML package to
 * LibreOffice or Apache POI.  Both libraries otherwise trust ZIP metadata and
 * may inflate every part before the caller can enforce task limits.
 */
final class OoxmlPackageGuard {
    private static final int BUFFER_SIZE = 64 * 1024;

    private OoxmlPackageGuard() { }

    static void requireSafe(Path packagePath, ParseLimits limits) throws IOException {
        requireSafe(packagePath, limits, true, true);
    }

    static void requireSafeOutput(Path packagePath, ParseLimits limits) throws IOException {
        // Generated packages are trusted and can legitimately compress much
        // better than an uploaded package (for example, repeated CSV values
        // in sharedStrings.xml). Keep size/path checks, but do not reject a
        // valid result solely because it exceeds the upload ratio heuristic.
        requireSafe(packagePath, limits, false, false);
    }

    private static void requireSafe(Path packagePath, ParseLimits limits, boolean enforceArchiveLimit,
                                    boolean enforceCompressionRatio) throws IOException {
        if (packagePath == null || !Files.isRegularFile(packagePath)) {
            throw new IOException("OOXML 文件不存在");
        }
        long archiveBytes = Files.size(packagePath);
        long archiveLimit = enforceArchiveLimit ? limits.maxArchiveBytes() : limits.maxExpandedBytes();
        long entryLimit = enforceArchiveLimit ? limits.maxEntryBytes() : limits.maxExpandedBytes();
        if (archiveBytes <= 0 || archiveBytes > archiveLimit) {
            throw new IOException("OOXML 文件为空或超过文件限制：" + archiveBytes + " > "
                    + archiveLimit);
        }
        long expandedBytes = 0;
        int entries = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile archive = new ZipFile(packagePath.toFile())) {
            var iterator = archive.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (++entries > limits.maxEntries()) {
                    throw new IOException("OOXML ZIP 条目数量超过限制：" + entries + " > " + limits.maxEntries());
                }
                String name = entry.getName();
                requireSafeName(name);
                if (!names.add(name)) {
                    throw new IOException("OOXML ZIP 包含重复条目：" + name);
                }
                if (entry.isDirectory()) continue;

                long declared = entry.getSize();
                if (declared > entryLimit) {
                    throw new IOException("OOXML ZIP 单个条目超过解压限制：" + name);
                }
                long compressed = entry.getCompressedSize();
                if (enforceCompressionRatio && declared >= 0 && compressed >= 0) {
                    requireCompressionRatio(name, declared, compressed, limits.maxCompressionRatio());
                }

                long actual = 0;
                try (InputStream input = archive.getInputStream(entry)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read == 0) continue;
                        actual = addWithinLimit(actual, read, entryLimit,
                                "OOXML ZIP 单个条目超过解压限制：" + name);
                        expandedBytes = addWithinLimit(expandedBytes, read, limits.maxExpandedBytes(),
                                "OOXML ZIP 总解压大小超过限制");
                    }
                }
                if (declared >= 0 && actual != declared) {
                    throw new IOException("OOXML ZIP 条目大小声明不一致：" + name);
                }
                if (enforceCompressionRatio && compressed >= 0) {
                    requireCompressionRatio(name, actual, compressed, limits.maxCompressionRatio());
                }
            }
        }
    }

    private static void requireSafeName(String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            throw new IOException("OOXML ZIP 条目名称无效");
        }
        String slashName = name.replace('\\', '/');
        if (slashName.startsWith("/") || slashName.startsWith("//")
                || slashName.matches("^[A-Za-z]:/.*")) {
            throw new IOException("OOXML ZIP 条目路径必须为相对路径：" + name);
        }
        for (String segment : slashName.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new IOException("OOXML ZIP 条目包含路径穿越：" + name);
            }
        }
        try {
            Path path = Path.of(slashName);
            if (path.isAbsolute() || path.normalize().startsWith("..")) {
                throw new IOException("OOXML ZIP 条目路径无效：" + name);
            }
        } catch (InvalidPathException error) {
            throw new IOException("OOXML ZIP 条目路径无效：" + name, error);
        }
    }

    private static void requireCompressionRatio(String name, long expanded, long compressed,
                                                double maxRatio) throws IOException {
        if (expanded <= 0) return;
        if (compressed <= 0 || ((double) expanded / (double) compressed) > maxRatio) {
            throw new IOException("OOXML ZIP 条目压缩比异常：" + name);
        }
    }

    private static long addWithinLimit(long current, long increment, long max, String message)
            throws IOException {
        if (increment < 0 || current > max - increment) throw new IOException(message);
        return current + increment;
    }
}
