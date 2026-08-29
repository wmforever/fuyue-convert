package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.nio.file.Path;
import java.util.List;

/** Keeps LibreOffice-only routes visible with an actionable local dependency state. */
final class UnavailableOfficeConverter implements FileConverter {
    private static final String SETUP_MESSAGE =
            "PPTX 转 PDF 需要本地 LibreOffice；请安装并启用 LibreOffice，或通过 "
                    + "FORMAT_CONVERTER_OFFICE_BINARY 配置 soffice 可执行文件。";
    private final ConversionRoute route;

    UnavailableOfficeConverter(DocumentFormat source, DocumentFormat target) {
        if (source != DocumentFormat.PPTX || target != DocumentFormat.PDF) {
            throw new IllegalArgumentException("当前仅支持声明 PPTX 到 PDF 的 Office 能力");
        }
        this.route = ConversionRoute.unavailable(source, target,
                "使用本地 LibreOffice 将 PPTX 高保真导出为 PDF；完成安装或配置后即可使用。",
                QualityLevel.BETA, ConversionStrategy.FIDELITY, List.of("libreoffice"),
                List.of(SETUP_MESSAGE));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        throw new ConversionFailureException("OFFICE_ENGINE_UNAVAILABLE", SETUP_MESSAGE);
    }
}
