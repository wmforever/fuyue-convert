package cn.tensafe.ofd2word.parser;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class SafeOfdExtractor {
    private static final int BUFFER_SIZE = 16 * 1024;

    public SafeOfdPackage extract(Path archive, Path destination, ParseLimits limits) throws OfdParseException {
        try {
            validateArchiveFile(archive, limits);
            Path root = Files.createDirectories(destination).toAbsolutePath().normalize();
            long total = 0;
            int entries = 0;
            byte[] buffer = new byte[BUFFER_SIZE];

            try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
                 ZipArchiveInputStream zip = new ZipArchiveInputStream(raw, StandardCharsets.UTF_8.name(), true, true)) {
                ZipArchiveEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries++;
                    if (entries > limits.maxEntries()) fail("OFD_TOO_MANY_ENTRIES", "OFD 压缩包条目数超过限制");
                    if (!zip.canReadEntryData(entry)) fail("OFD_UNSUPPORTED_ZIP_ENTRY", "OFD 包含不支持的压缩条目");
                    if (entry.isUnixSymlink()) fail("OFD_LINK_ENTRY_FORBIDDEN", "OFD 包含符号链接");

                    String name = normalizeEntryName(entry.getName());
                    Path target = root.resolve(name).normalize();
                    if (!target.startsWith(root)) fail("OFD_ZIP_SLIP", "OFD 包含越界路径");
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    long entryBytes = 0;
                    try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            entryBytes += read;
                            total += read;
                            if (entryBytes > limits.maxEntryBytes()) fail("OFD_ENTRY_TOO_LARGE", "OFD 单个条目解压后超过限制");
                            if (total > limits.maxExpandedBytes()) fail("OFD_EXPANDED_TOO_LARGE", "OFD 解压总量超过限制");
                            out.write(buffer, 0, read);
                        }
                    }
                    long compressed = entry.getCompressedSize();
                    if (compressed > 0 && entryBytes / (double) compressed > limits.maxCompressionRatio()) {
                        fail("OFD_COMPRESSION_BOMB", "OFD 条目压缩比超过限制");
                    }
                    if (isXml(name)) rejectDangerousXml(target);
                }
            }
            if (!Files.isRegularFile(root.resolve("OFD.xml"))) {
                fail("INVALID_OFD_STRUCTURE", "OFD 根目录缺少 OFD.xml");
            }
            return new SafeOfdPackage(root, total, entries);
        } catch (OfdParseException e) {
            throw e;
        } catch (IOException e) {
            throw new OfdParseException("OFD_EXTRACT_FAILED", "OFD 解压或校验失败", e);
        }
    }

    private void validateArchiveFile(Path archive, ParseLimits limits) throws IOException, OfdParseException {
        if (!Files.isRegularFile(archive)) fail("OFD_FILE_MISSING", "上传文件不存在");
        long size = Files.size(archive);
        if (size == 0 || size > limits.maxArchiveBytes()) fail("OFD_FILE_SIZE_INVALID", "OFD 文件为空或超过上传限制");
        byte[] magic = new byte[4];
        try (InputStream in = Files.newInputStream(archive)) {
            if (in.read(magic) != 4 || magic[0] != 'P' || magic[1] != 'K'
                    || !((magic[2] == 3 && magic[3] == 4) || (magic[2] == 5 && magic[3] == 6))) {
                fail("INVALID_OFD_MAGIC", "文件不是有效的 ZIP/OFD 容器");
            }
        }
    }

    private String normalizeEntryName(String raw) throws OfdParseException {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) fail("OFD_INVALID_ENTRY_NAME", "OFD 包含非法条目名");
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) fail("OFD_ABSOLUTE_PATH", "OFD 包含绝对路径");
        for (String part : name.split("/")) {
            if (part.equals("..")) fail("OFD_ZIP_SLIP", "OFD 包含父目录路径");
        }
        return name;
    }

    private void rejectDangerousXml(Path xml) throws IOException, OfdParseException {
        if (Files.size(xml) > 8L * 1024 * 1024) fail("OFD_XML_TOO_LARGE", "OFD XML 条目超过限制");
        String prefix = Files.readString(xml, StandardCharsets.UTF_8);
        String upper = prefix.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY") || upper.contains("XINCLUDE")) {
            fail("OFD_UNSAFE_XML", "OFD XML 包含被禁止的外部实体或包含声明");
        }
    }

    private boolean isXml(String name) { return name.toLowerCase(Locale.ROOT).endsWith(".xml"); }
    private static void fail(String code, String message) throws OfdParseException { throw new OfdParseException(code, message); }
}

