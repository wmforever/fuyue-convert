package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Path;
import java.util.List;

/** Keeps an explicitly requested image OCR route visible while returning its precise capability failure. */
final class UnavailableOcrConverter implements FileConverter {
    private final ConversionRoute route;
    private final TesseractOcrConverter.Capability capability;

    UnavailableOcrConverter(DocumentFormat source, DocumentFormat target,
                            TesseractOcrConverter.Capability capability) {
        this.capability = capability;
        this.route = ConversionRoute.of(source, target,
                "图片 OCR 需要显式配置且能力检测通过的本地 Tesseract OCR。",
                QualityLevel.EXPERIMENTAL, ConversionStrategy.EXTRACTION, List.of("tesseract"),
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
