package com.fuyue.formatconverter.task;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ImageMetadataReader {
    private static final double DEFAULT_DPI = 96d;

    private ImageMetadataReader() { }

    static ImageMetadata read(Path path, DocumentFormat format) throws IOException {
        RawMetadata raw = format == DocumentFormat.JPG ? readJpeg(path) : readPng(path);
        boolean validDpi = validDpi(raw.dpiX()) && validDpi(raw.dpiY());
        return new ImageMetadata(raw.orientation() >= 1 && raw.orientation() <= 8 ? raw.orientation() : 1,
                validDpi ? raw.dpiX() : DEFAULT_DPI,
                validDpi ? raw.dpiY() : DEFAULT_DPI,
                validDpi && raw.embeddedDpi());
    }

    private static boolean validDpi(double value) {
        return Double.isFinite(value) && value >= 36d && value <= 1_200d;
    }

    private static RawMetadata readPng(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            byte[] signature = in.readNBytes(8);
            if (signature.length != 8) throw new EOFException("PNG 文件头不完整");
            while (true) {
                long length = Integer.toUnsignedLong(in.readInt());
                byte[] typeBytes = in.readNBytes(4);
                if (typeBytes.length != 4) break;
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                if (length > 16L * 1024 * 1024) throw new IOException("PNG 元数据块过大");
                if ("pHYs".equals(type) && length == 9) {
                    long x = Integer.toUnsignedLong(in.readInt());
                    long y = Integer.toUnsignedLong(in.readInt());
                    int unit = in.readUnsignedByte();
                    in.skipNBytes(4);
                    if (unit == 1 && x > 0 && y > 0) {
                        return new RawMetadata(1, x * 0.0254d, y * 0.0254d, true);
                    }
                    return RawMetadata.defaults();
                }
                if ("IDAT".equals(type) || "IEND".equals(type)) break;
                in.skipNBytes(length + 4);
            }
        }
        return RawMetadata.defaults();
    }

    private static RawMetadata readJpeg(Path path) throws IOException {
        int orientation = 1;
        double dpiX = Double.NaN;
        double dpiY = Double.NaN;
        boolean embedded = false;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            if (in.readUnsignedShort() != 0xffd8) throw new IOException("JPEG 文件头无效");
            while (true) {
                int prefix;
                do { prefix = in.readUnsignedByte(); } while (prefix != 0xff);
                int marker;
                do { marker = in.readUnsignedByte(); } while (marker == 0xff);
                if (marker == 0xd9 || marker == 0xda) break;
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
                int length = in.readUnsignedShort();
                if (length < 2) throw new IOException("JPEG 元数据段长度无效");
                byte[] payload = in.readNBytes(length - 2);
                if (payload.length != length - 2) throw new EOFException("JPEG 元数据段不完整");
                if (marker == 0xe0 && startsWith(payload, "JFIF\0".getBytes(StandardCharsets.US_ASCII))
                        && payload.length >= 12) {
                    int unit = payload[7] & 0xff;
                    int x = u16be(payload, 8);
                    int y = u16be(payload, 10);
                    if (x > 0 && y > 0 && (unit == 1 || unit == 2)) {
                        dpiX = unit == 1 ? x : x * 2.54d;
                        dpiY = unit == 1 ? y : y * 2.54d;
                        embedded = true;
                    }
                } else if (marker == 0xe1 && startsWith(payload,
                        new byte[]{'E', 'x', 'i', 'f', 0, 0})) {
                    ExifMetadata exif = parseExif(payload);
                    orientation = exif.orientation();
                    if (validDpi(exif.dpiX()) && validDpi(exif.dpiY())) {
                        dpiX = exif.dpiX();
                        dpiY = exif.dpiY();
                        embedded = true;
                    }
                }
            }
        } catch (EOFException ignored) {
            // A valid image decoder will report truncation later; metadata absence is not fatal here.
        }
        return new RawMetadata(orientation, dpiX, dpiY, embedded);
    }

    private static ExifMetadata parseExif(byte[] payload) {
        try {
            int tiff = 6;
            if (payload.length < tiff + 8) return ExifMetadata.defaults();
            ByteOrder order;
            if (payload[tiff] == 'I' && payload[tiff + 1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
            else if (payload[tiff] == 'M' && payload[tiff + 1] == 'M') order = ByteOrder.BIG_ENDIAN;
            else return ExifMetadata.defaults();
            ByteBuffer buffer = ByteBuffer.wrap(payload).order(order);
            if (Short.toUnsignedInt(buffer.getShort(tiff + 2)) != 42) return ExifMetadata.defaults();
            long ifdOffset = Integer.toUnsignedLong(buffer.getInt(tiff + 4));
            int ifd = Math.toIntExact(tiff + ifdOffset);
            int entries = Short.toUnsignedInt(buffer.getShort(ifd));
            int orientation = 1;
            double x = Double.NaN;
            double y = Double.NaN;
            int unit = 2;
            for (int index = 0; index < Math.min(entries, 256); index++) {
                int entry = ifd + 2 + index * 12;
                if (entry < 0 || entry + 12 > payload.length) break;
                int tag = Short.toUnsignedInt(buffer.getShort(entry));
                int type = Short.toUnsignedInt(buffer.getShort(entry + 2));
                long count = Integer.toUnsignedLong(buffer.getInt(entry + 4));
                if ((tag == 0x0112 || tag == 0x0128) && type == 3 && count == 1) {
                    int value = Short.toUnsignedInt(buffer.getShort(entry + 8));
                    if (tag == 0x0112) orientation = value;
                    else unit = value;
                } else if ((tag == 0x011a || tag == 0x011b) && type == 5 && count == 1) {
                    long offset = Integer.toUnsignedLong(buffer.getInt(entry + 8));
                    int rational = Math.toIntExact(tiff + offset);
                    if (rational >= 0 && rational + 8 <= payload.length) {
                        long numerator = Integer.toUnsignedLong(buffer.getInt(rational));
                        long denominator = Integer.toUnsignedLong(buffer.getInt(rational + 4));
                        double value = denominator == 0 ? Double.NaN : (double) numerator / denominator;
                        if (tag == 0x011a) x = value;
                        else y = value;
                    }
                }
            }
            if (unit == 3) {
                x *= 2.54d;
                y *= 2.54d;
            } else if (unit != 2) {
                x = Double.NaN;
                y = Double.NaN;
            }
            return new ExifMetadata(orientation, x, y);
        } catch (RuntimeException ignored) {
            return ExifMetadata.defaults();
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static int u16be(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    record ImageMetadata(int orientation, double dpiX, double dpiY, boolean embeddedDpi) {
        boolean swapsAxes() { return orientation >= 5; }
    }

    private record RawMetadata(int orientation, double dpiX, double dpiY, boolean embeddedDpi) {
        static RawMetadata defaults() { return new RawMetadata(1, Double.NaN, Double.NaN, false); }
    }

    private record ExifMetadata(int orientation, double dpiX, double dpiY) {
        static ExifMetadata defaults() { return new ExifMetadata(1, Double.NaN, Double.NaN); }
    }
}
