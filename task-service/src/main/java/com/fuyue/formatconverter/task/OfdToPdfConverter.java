package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;

import java.nio.file.Path;
import java.util.List;

public final class OfdToPdfConverter implements FileConverter {
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final FixedLayoutPdfRenderer renderer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.PDF,
            "按 OFD 页面坐标渲染文字、图片、签章外观和路径线条，生成固定版式 PDF。",
            QualityLevel.BETA, ConversionStrategy.FIDELITY, List.of(),
            List.of("字体使用内置字体替代", "复杂填充、渐变、透明度、裁剪和部分弧线路径仍需完善"));

    public OfdToPdfConverter(SafeOfdExtractor extractor, OfdParser parser, PageLayoutAnalyzer analyzer) {
        this.extractor = extractor;
        this.parser = parser;
        java.util.Objects.requireNonNull(analyzer, "analyzer");
        this.renderer = new FixedLayoutPdfRenderer();
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 20);
        var safe = extractor.extract(input.path(), workDir, limits);
        var parsed = parser.parse(safe, input.displayName(), limits);
        progress.update(TaskStage.RENDERING, 60);
        renderer.render(parsed, outputPath);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "OFD 转 PDF");
        List<ConversionWarning> warnings = new java.util.ArrayList<>(parsed.warnings());
        parsed.pages().forEach(page -> warnings.addAll(page.warnings()));
        if (parsed.pages().stream().anyMatch(page -> !page.textBlocks().isEmpty())) {
            warnings.add(ConversionWarning.of(WarningCode.FONT_SUBSTITUTED,
                    "OFD 字体已使用内置 PDF 字体替代；字形宽度和少数字符可能与原文件不同。", null));
        }
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.ofd$", ".pdf"),
                parsed.pages().size(), warnings);
    }
}
