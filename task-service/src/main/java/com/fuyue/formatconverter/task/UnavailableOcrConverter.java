package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Path;
import java.util.List;

/** Keeps image OCR routes visible while returning the precise local capability state. */
final class UnavailableOcrConverter implements FileConverter {
    private final ConversionRoute route;
    private final TesseractOcrConverter.Capability capability;

    UnavailableOcrConverter(DocumentFormat source, DocumentFormat target,
                            TesseractOcrConverter.Capability capability) {
        this.capability = capability;
        ConversionStrategy strategy = target == DocumentFormat.DOCX
                ? ConversionStrategy.EDITABLE : ConversionStrategy.EXTRACTION;
        this.route = ConversionRoute.unavailable(source, target,
                capability.enabled()
                        ? "图片 OCR 已启用，但当前本地 Tesseract 能力检测未通过。"
                        : "图片 OCR 当前未启用；启用并配置本地 Tesseract 后即可使用。",
                QualityLevel.EXPERIMENTAL, strategy, List.of("tesseract"),
                List.of(capability.message()));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        throw new ConversionFailureException(
                capability.errorCode() == null ? "OCR_ENGINE_UNAVAILABLE" : capability.errorCode(),
                capability.message());
    }
}
