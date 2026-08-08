package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class XlsxToPdfConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.XLSX, DocumentFormat.PDF,
            "将 Excel 第一个工作表导出为基础表格 PDF。");

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 35);
        DataFormatter formatter = new DataFormatter();
        List<String> lines = new ArrayList<>();
        long cells = 0;
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(input.path()))) {
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
                        String value = cell == null ? "" : formatter.formatCellValue(cell);
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
        PdfSupport.writeTextPdf(lines, outputPath);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "XLSX 转 PDF");
        return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.xlsx$", ".pdf"), null, List.of());
    }
}
