package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CsvToXlsxConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.CSV, DocumentFormat.XLSX,
            "将 CSV 表格转换为 Excel XLSX 工作簿，保留单元格文本内容。");

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 25);
        List<List<String>> rows = CsvSupport.parse(Files.readString(input.path(), StandardCharsets.UTF_8), limits);
        progress.update(TaskStage.RENDERING, 75);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            for (int r = 0; r < rows.size(); r++) {
                ConversionGuards.requireSpreadsheetRow(r, limits);
                Row row = sheet.createRow(r);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    ConversionGuards.requireSpreadsheetColumn(c);
                    ConversionGuards.requireCellText(values.get(c));
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            try (var out = Files.newOutputStream(outputPath)) { workbook.write(out); }
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "CSV 转 XLSX");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), null, List.of());
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.csv$", "") + ".xlsx";
    }

    static final class CsvSupport {
        private CsvSupport() {}

        static List<List<String>> parse(String text) {
            try {
                return parse(text, ParseLimits.defaults());
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        static List<List<String>> parse(String text, ParseLimits limits) throws IOException {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            long cells = 0;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(ch);
                    }
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == ',') {
                    ConversionGuards.requireSpreadsheetColumn(row.size());
                    ConversionGuards.requireCellText(cell.toString());
                    row.add(cell.toString());
                    cells++;
                    ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                    cell.setLength(0);
                } else if (ch == '\n' || ch == '\r') {
                    ConversionGuards.requireSpreadsheetColumn(row.size());
                    ConversionGuards.requireCellText(cell.toString());
                    row.add(cell.toString());
                    cells++;
                    ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                    cell.setLength(0);
                    ConversionGuards.requireSpreadsheetRow(rows.size(), limits);
                    rows.add(row);
                    row = new ArrayList<>();
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                } else {
                    cell.append(ch);
                }
            }
            if (!row.isEmpty() || cell.length() > 0 || text.endsWith(",")) {
                ConversionGuards.requireSpreadsheetColumn(row.size());
                ConversionGuards.requireCellText(cell.toString());
                row.add(cell.toString());
                cells++;
                ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                ConversionGuards.requireSpreadsheetRow(rows.size(), limits);
                rows.add(row);
            }
            return rows;
        }

        static String write(List<List<String>> rows) {
            StringBuilder out = new StringBuilder();
            for (List<String> row : rows) {
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) out.append(',');
                    out.append(escape(row.get(i)));
                }
                out.append(System.lineSeparator());
            }
            return out.toString();
        }

        private static String escape(String value) {
            String safe = value == null ? "" : value;
            if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
                return "\"" + safe.replace("\"", "\"\"") + "\"";
            }
            return safe;
        }
    }
}
