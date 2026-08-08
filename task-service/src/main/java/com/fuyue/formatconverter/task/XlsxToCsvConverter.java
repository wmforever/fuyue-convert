package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class XlsxToCsvConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.XLSX, DocumentFormat.CSV,
            "将 Excel XLSX 第一个工作表导出为 UTF-8 CSV。");

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        DataFormatter formatter = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        long cells = 0;
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(input.path()))) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet != null) {
                int lastRow = sheet.getLastRowNum();
                for (int r = 0; r <= lastRow; r++) {
                    ConversionGuards.requireSpreadsheetRow(r, limits);
                    Row row = sheet.getRow(r);
                    List<String> values = new ArrayList<>();
                    if (row != null) {
                        int lastCell = Math.max(0, row.getLastCellNum());
                        for (int c = 0; c < Math.max(0, lastCell); c++) {
                            ConversionGuards.requireSpreadsheetColumn(c);
                            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                            String value = cell == null ? "" : formatter.formatCellValue(cell);
                            ConversionGuards.requireCellText(value);
                            values.add(value);
                            cells++;
                            ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                        }
                    }
                    rows.add(values);
                }
            }
        }
        progress.update(TaskStage.RENDERING, 80);
        Files.writeString(outputPath, CsvToXlsxConverter.CsvSupport.write(rows), StandardCharsets.UTF_8);
        ConversionGuards.requireOutputFile(outputPath, limits, "XLSX 转 CSV");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), null, List.of());
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.xlsx$", "") + ".csv";
    }
}
