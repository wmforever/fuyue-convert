package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TextToDocxConverter implements FileConverter {
    private static final String DOCX_FONT_ENV = "FORMAT_CONVERTER_DOCX_FONT";
    private static final String DOCX_CJK_FONT_ENV = "FORMAT_CONVERTER_DOCX_CJK_FONT";

    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.TXT, DocumentFormat.DOCX,
            "将 UTF-8、带 BOM 的 UTF-16 或 GB18030 文本转换为可编辑 Word DOCX，保留显式分页。",
            QualityLevel.STABLE, ConversionStrategy.CONTENT, List.of(),
            List.of("仅生成基础段落样式", "无 BOM 且非 UTF-8 的文本按 GB18030 解码并返回警告"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 25);
        TextInputReader.DecodedText decoded = TextInputReader.read(input.path(), limits);
        progress.update(TaskStage.RENDERING, 75);
        try (XWPFDocument document = new XWPFDocument()) {
            for (int pageIndex = 0; pageIndex < decoded.pages().size(); pageIndex++) {
                if (pageIndex > 0) {
                    XWPFRun pageBreak = document.createParagraph().createRun();
                    configureFonts(pageBreak);
                    pageBreak.addBreak(BreakType.PAGE);
                }
                for (String line : decoded.pages().get(pageIndex)) {
                    XWPFRun run = document.createParagraph().createRun();
                    configureFonts(run);
                    run.setText(line);
                }
            }
            try (var out = Files.newOutputStream(outputPath)) { document.write(out); }
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "TXT 转 DOCX");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.txt$", ".docx"),
                null, decoded.warnings());
    }

    private static void configureFonts(XWPFRun run) {
        String latin = configuredFont(DOCX_FONT_ENV, "Arial");
        String cjk = configuredFont(DOCX_CJK_FONT_ENV, "Microsoft YaHei");
        run.setFontFamily(latin, XWPFRun.FontCharRange.ascii);
        run.setFontFamily(latin, XWPFRun.FontCharRange.hAnsi);
        run.setFontFamily(cjk, XWPFRun.FontCharRange.eastAsia);
    }

    private static String configuredFont(String environmentName, String fallback) {
        String configured = System.getenv(environmentName);
        return configured == null || configured.isBlank() ? fallback : configured.strip();
    }
}
