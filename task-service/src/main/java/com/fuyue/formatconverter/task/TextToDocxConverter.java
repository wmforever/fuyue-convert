package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TextToDocxConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.TXT, DocumentFormat.DOCX,
            "将 UTF-8 文本转换为可编辑 Word DOCX。",
            QualityLevel.STABLE, ConversionStrategy.CONTENT, List.of(), List.of("仅生成基础段落样式"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 25);
        List<String> lines = Files.readAllLines(input.path(), StandardCharsets.UTF_8);
        progress.update(TaskStage.RENDERING, 75);
        try (XWPFDocument document = new XWPFDocument()) {
            for (String line : lines) document.createParagraph().createRun().setText(line);
            try (var out = Files.newOutputStream(outputPath)) { document.write(out); }
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "TXT 转 DOCX");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.txt$", ".docx"), null, List.of());
    }
}
