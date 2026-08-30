package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class XlsxToPdfConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.XLSX, DocumentFormat.PDF,
            "将 Excel 第一个工作表导出为基础表格 PDF。",
            QualityLevel.BETA, ConversionStrategy.CONTENT, List.of(), List.of("Java 兜底路线仅导出第一个工作表文本"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 35);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        boolean[] formulas = {false};
        long cells = 0;
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(input.path()))) {
            boolean use1904Windowing = uses1904Windowing(workbook);
            if (workbook.getNumberOfSheets() > 0) {
                Sheet sheet = workbook.getSheetAt(0);
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    ConversionGuards.requireSpreadsheetRow(r, limits);
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        lines.add("");
                        continue;
                    }
                    List<String> values = new ArrayList<>();
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        ConversionGuards.requireSpreadsheetColumn(c);
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String value = cell == null ? "" : cellText(cell, formatter, formulas, use1904Windowing);
                        ConversionGuards.requireCellText(value);
                        values.add(value);
                        cells++;
                        ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                    }
                    lines.add(String.join("    ", values));
                }
            }
        }
        progress.update(TaskStage.RENDERING, 80);
        int pageCount = PdfSupport.writeTextPdfPages(List.of(lines), outputPath, limits.maxPages());
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "XLSX 转 PDF");
        List<ConversionWarning> warnings = formulas[0]
                ? List.of(ConversionWarning.of(WarningCode.FORMULA_RESULT_EXPORTED,
                "XLSX 公式已导出工作簿中保存的缓存结果，未在服务端重新计算。", null))
                : List.of();
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.xlsx$", ".pdf"),
                pageCount, warnings);
    }

    private String cellText(Cell cell, DataFormatter formatter, boolean[] formulas, boolean use1904Windowing) {
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        formulas[0] = true;
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString(),
                    use1904Windowing);
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue()).toUpperCase(Locale.ROOT);
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            case BLANK, _NONE, FORMULA -> "";
        };
    }

    private boolean uses1904Windowing(XSSFWorkbook workbook) {
        var definition = workbook.getCTWorkbook();
        return definition.isSetWorkbookPr() && definition.getWorkbookPr().getDate1904();
    }
}
