package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.TesseractOcrConverter;

public record OcrEngineStatus(boolean enabled, boolean available, String binaryName,
                              String version, String languages, String message) {
    public static OcrEngineStatus detect() {
        if (!TesseractOcrConverter.configuredEnabled()) {
            return new OcrEngineStatus(false, false, null, null, null, "本地 OCR 未启用");
        }
        return TesseractOcrConverter.configuredSettings()
                .map(settings -> new OcrEngineStatus(true, true,
                        settings.binary().getFileName().toString(), settings.version(), settings.languages(),
                        "本地 Tesseract OCR 可用"))
                .orElseGet(() -> new OcrEngineStatus(true, false, null, null, null,
                        "未找到可用的 Tesseract，或配置的语言包不完整"));
    }
}
