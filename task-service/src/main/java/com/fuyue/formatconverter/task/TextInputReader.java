package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class TextInputReader {
    private static final Charset GB18030 = Charset.forName("GB18030");

    private TextInputReader() { }

    static DecodedText read(Path path, ParseLimits limits) throws Exception {
        long declaredSize = Files.size(path);
        if (declaredSize > limits.maxArchiveBytes()) {
            throw new ConversionFailureException("TEXT_TOO_LARGE",
                    "TXT 文件超过限制：" + declaredSize + " > " + limits.maxArchiveBytes());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > limits.maxArchiveBytes()) {
            throw new ConversionFailureException("TEXT_TOO_LARGE",
                    "TXT 文件超过限制：" + bytes.length + " > " + limits.maxArchiveBytes());
        }

        Decoded decoded = decode(bytes);
        validateText(decoded.value());
        String normalized = decoded.value().replace("\r\n", "\n").replace('\r', '\n');
        String[] rawPages = normalized.split("\f", -1);
        if (rawPages.length > limits.maxPages()) {
            throw new ConversionFailureException("PAGE_LIMIT_EXCEEDED",
                    "TXT 显式分页数超过限制：" + rawPages.length + " > " + limits.maxPages());
        }
        List<List<String>> pages = new ArrayList<>(rawPages.length);
        for (String page : rawPages) pages.add(List.of(page.split("\n", -1)));

        List<ConversionWarning> warnings = decoded.guessed()
                ? List.of(ConversionWarning.of(WarningCode.TEXT_ENCODING_GUESSED,
                    "TXT 不符合 UTF-8，已按 GB18030 严格解码；请核对源文件编码。", null))
                : List.of();
        return new DecodedText(List.copyOf(pages), decoded.charset().name(), warnings);
    }

    private static Decoded decode(byte[] bytes) throws ConversionFailureException {
        try {
            if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
                return new Decoded(decodeStrict(bytes, 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8, false);
            }
            if (startsWith(bytes, 0xFF, 0xFE)) {
                return new Decoded(decodeStrict(bytes, 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, false);
            }
            if (startsWith(bytes, 0xFE, 0xFF)) {
                return new Decoded(decodeStrict(bytes, 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE, false);
            }
            try {
                return new Decoded(decodeStrict(bytes, 0, StandardCharsets.UTF_8), StandardCharsets.UTF_8, false);
            } catch (CharacterCodingException ignored) {
                return new Decoded(decodeStrict(bytes, 0, GB18030), GB18030, true);
            }
        } catch (CharacterCodingException e) {
            throw new ConversionFailureException("TEXT_ENCODING_UNSUPPORTED",
                    "TXT 编码无效；支持 UTF-8、带 BOM 的 UTF-16LE/UTF-16BE，以及可严格解码的 GB18030。");
        }
    }

    private static String decodeStrict(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    private static void validateText(String value) throws ConversionFailureException {
        int disallowed = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == 0) {
                throw new ConversionFailureException("TEXT_BINARY_CONTENT",
                        "TXT 包含 NUL 二进制字符，已拒绝转换。");
            }
            if (Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\r' && codePoint != '\t' && codePoint != '\f') {
                disallowed++;
            }
        }
        if (disallowed > 4 && disallowed * 100L > Math.max(1, value.codePointCount(0, value.length()))) {
            throw new ConversionFailureException("TEXT_BINARY_CONTENT",
                    "TXT 包含过多二进制控制字符，已拒绝转换。");
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) return false;
        }
        return true;
    }

    record DecodedText(List<List<String>> pages, String charsetName, List<ConversionWarning> warnings) {
        DecodedText {
            pages = pages.stream().map(List::copyOf).toList();
            warnings = List.copyOf(warnings);
        }
    }

    private record Decoded(String value, Charset charset, boolean guessed) { }
}
