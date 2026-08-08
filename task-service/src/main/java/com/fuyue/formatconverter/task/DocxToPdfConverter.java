package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DocxToPdfConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.DOCX, DocumentFormat.PDF,
            "将 Word DOCX 文本内容导出为基础 PDF。",
            QualityLevel.BETA, ConversionStrategy.CONTENT, List.of(), List.of("Java 兜底路线仅导出文本内容，不保留复杂版式"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 40);
        List<String> lines = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(input.path()))) {
            document.getParagraphs().forEach(paragraph -> lines.add(paragraph.getText()));
            document.getTables().forEach(table -> table.getRows().forEach(row ->
                    lines.add(row.getTableCells().stream().map(cell -> cell.getText().replace('\n', ' '))
                            .reduce((a, b) -> a + "    " + b).orElse(""))));
        }
        progress.update(TaskStage.RENDERING, 80);
        PdfSupport.writeTextPdf(lines, outputPath);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "DOCX 转 PDF");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.docx$", ".pdf"), null, List.of());
    }
}
