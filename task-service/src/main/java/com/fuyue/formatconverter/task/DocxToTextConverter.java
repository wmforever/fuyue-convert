package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DocxToTextConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.DOCX, DocumentFormat.TXT,
            "从 Word DOCX 提取段落和表格文本为 UTF-8 TXT。");

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 35);
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(input.path()))) {
            document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append(System.lineSeparator()));
            document.getTables().forEach(table -> table.getRows().forEach(row -> {
                String line = row.getTableCells().stream().map(cell -> cell.getText().replace('\n', ' ')).reduce((a, b) -> a + "\t" + b).orElse("");
                text.append(line).append(System.lineSeparator());
            }));
        }
        progress.update(TaskStage.RENDERING, 80);
        Files.writeString(outputPath, text.toString(), StandardCharsets.UTF_8);
        ConversionGuards.requireOutputFile(outputPath, limits, "DOCX 转 TXT");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.docx$", ".txt"), null, List.of());
    }
}
