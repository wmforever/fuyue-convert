package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorMessageSanitizerTest {
    @Test void removesUnixAndWindowsAbsolutePathsAndControlCharacters() {
        String message = ErrorMessageSanitizer.from(new IOException(
                "读取 /Users/alice/private/source.pdf 和 C:\\Users\\alice\\secret.docx 失败\n下一行"));

        assertEquals("读取 <path> 和 <path> 失败 下一行", message);
        assertFalse(message.contains("alice"));
        assertFalse(message.contains("\n"));
    }

    @Test void boundsPersistedMessageLength() {
        assertTrue(ErrorMessageSanitizer.sanitize("x".repeat(2_000)).length() <= 1_003);
    }
}
