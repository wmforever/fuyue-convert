package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.TesseractOcrConverter;

import java.util.Set;

public record OcrEngineStatus(boolean enabled, boolean available, String binaryName,
                              String version, String requestedLanguages, Set<String> availableLanguages,
                              Long timeoutSeconds, Integer maxConcurrency, Long maxImagePixels, Double minimumConfidence,
                              Boolean bundled, String errorCode, String message) {
    public OcrEngineStatus {
        availableLanguages = availableLanguages == null ? Set.of() : Set.copyOf(availableLanguages);
    }

    public static OcrEngineStatus detect() {
        TesseractOcrConverter.Capability capability = TesseractOcrConverter.detectConfigured();
        TesseractOcrConverter.Settings settings = capability.settings();
        return new OcrEngineStatus(capability.enabled(), capability.available(), capability.binaryName(),
                capability.version(), capability.requestedLanguages(), capability.availableLanguages(),
                settings == null ? null : settings.timeout().toSeconds(),
                settings == null ? null : settings.maxConcurrency(),
                settings == null ? null : settings.maxImagePixels(),
                settings == null ? null : settings.minimumConfidence(),
                settings == null ? null : settings.bundled(),
                capability.errorCode(), capability.message());
    }
}
