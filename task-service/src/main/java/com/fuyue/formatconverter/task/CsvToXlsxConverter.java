package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CsvToXlsxConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.CSV, DocumentFormat.XLSX,
            "将 UTF-8、带 BOM 的 UTF-16 或 GB18030 CSV 转换为 XLSX，自动识别常见分隔符。",
            QualityLevel.STABLE, ConversionStrategy.DATA, List.of(),
            List.of("CSV 单元格统一写为文本，避免公式注入且不猜测日期和数值类型", "不推断样式"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 25);
        TextInputReader.DecodedContent decoded = TextInputReader.readContent(input.path(), limits);
        char delimiter = CsvSupport.detectDelimiter(decoded.value());
        List<List<String>> rows = CsvSupport.parse(decoded.value(), delimiter, limits);
        List<ConversionWarning> warnings = new ArrayList<>(decoded.warnings());
        if (delimiter != ',') {
            warnings.add(ConversionWarning.of(WarningCode.CSV_DELIMITER_DETECTED,
                    "检测到 CSV 分隔符：" + CsvSupport.delimiterName(delimiter), null));
        }

        progress.update(TaskStage.RENDERING, 75);
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (workbook) {
            Sheet sheet = workbook.createSheet("Sheet1");
            for (int r = 0; r < rows.size(); r++) {
                ConversionGuards.requireSpreadsheetRow(r, limits);
                Row row = sheet.createRow(r);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    ConversionGuards.requireSpreadsheetColumn(c);
                    ConversionGuards.requireCellText(values.get(c));
                    // Always write CSV input as a shared string. Values beginning with =, +, - or @
                    // must never become executable spreadsheet formulas implicitly.
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            try (var out = Files.newOutputStream(outputPath)) { workbook.write(out); }
        } finally {
            workbook.dispose();
        }
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "CSV 转 XLSX");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), null, warnings);
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.csv$", "") + ".xlsx";
    }

    static final class CsvSupport {
        private static final char[] DELIMITERS = {',', '\t', ';', '|'};

        private CsvSupport() { }

        static List<List<String>> parse(String text) {
            try {
                return parse(text, ',', ParseLimits.defaults());
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        static List<List<String>> parse(String text, ParseLimits limits) throws IOException {
            return parse(text, ',', limits);
        }

        static List<List<String>> parse(String text, char delimiter, ParseLimits limits) throws IOException {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            boolean quoteClosed = false;
            boolean cellStarted = false;
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
                            quoteClosed = true;
                        }
                    } else {
                        cell.append(ch);
                    }
                    continue;
                }
                if (quoteClosed && ch != delimiter && ch != '\n' && ch != ' ' && ch != '\t') {
                    throw new IOException("CSV 引号字段结束后存在非法字符，位置 " + (i + 1));
                }
                if (quoteClosed && (ch == ' ' || ch == '\t') && ch != delimiter) continue;
                if (ch == '"') {
                    if (cellStarted || !cell.isEmpty()) {
                        throw new IOException("CSV 未转义引号，位置 " + (i + 1));
                    }
                    quoted = true;
                    cellStarted = true;
                } else if (ch == delimiter) {
                    cells = addCell(row, cell, cells, limits);
                    cellStarted = false;
                    quoteClosed = false;
                } else if (ch == '\n') {
                    cells = addCell(row, cell, cells, limits);
                    addRow(rows, row, limits);
                    row = new ArrayList<>();
                    cellStarted = false;
                    quoteClosed = false;
                } else {
                    cell.append(ch);
                    cellStarted = true;
                }
            }
            if (quoted) throw new IOException("CSV 引号字段未闭合");
            if (!row.isEmpty() || cellStarted || quoteClosed || (!text.isEmpty() && text.charAt(text.length() - 1) == delimiter)) {
                addCell(row, cell, cells, limits);
                addRow(rows, row, limits);
            }
            return rows;
        }

        private static long addCell(List<String> row, StringBuilder cell, long cells,
                                    ParseLimits limits) throws IOException {
            ConversionGuards.requireSpreadsheetColumn(row.size());
            String value = cell.toString();
            ConversionGuards.requireCellText(value);
            row.add(value);
            cell.setLength(0);
            ConversionGuards.requireSpreadsheetCellCount(++cells, limits);
            return cells;
        }

        private static void addRow(List<List<String>> rows, List<String> row,
                                   ParseLimits limits) throws IOException {
            ConversionGuards.requireSpreadsheetRow(rows.size(), limits);
            rows.add(row);
        }

        static char detectDelimiter(String text) {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            char selected = ',';
            long bestScore = -1;
            for (char candidate : DELIMITERS) {
                List<Integer> counts = delimiterCounts(text, candidate);
                Map<Integer, Integer> frequencies = new HashMap<>();
                for (int count : counts) if (count > 0) frequencies.merge(count, 1, Integer::sum);
                int modeCount = 0;
                int modeFrequency = 0;
                for (var entry : frequencies.entrySet()) {
                    if (entry.getValue() > modeFrequency
                            || (entry.getValue() == modeFrequency && entry.getKey() > modeCount)) {
                        modeCount = entry.getKey();
                        modeFrequency = entry.getValue();
                    }
                }
                long score = (long) modeFrequency * 1_000_000L + (long) modeCount * 1_000L
                        + counts.stream().filter(value -> value > 0).count();
                if (score > bestScore) {
                    bestScore = score;
                    selected = candidate;
                }
            }
            return selected;
        }

        private static List<Integer> delimiterCounts(String text, char delimiter) {
            List<Integer> result = new ArrayList<>();
            boolean quoted = false;
            int count = 0;
            for (int i = 0; i < text.length() && result.size() < 32; i++) {
                char ch = text.charAt(i);
                if (ch == '"') {
                    if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                    else quoted = !quoted;
                } else if (!quoted && ch == delimiter) {
                    count++;
                } else if (!quoted && ch == '\n') {
                    result.add(count);
                    count = 0;
                }
            }
            if (result.size() < 32) result.add(count);
            return result;
        }

        static String write(List<List<String>> rows) {
            StringBuilder out = new StringBuilder();
            for (List<String> row : rows) {
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) out.append(',');
                    out.append(escape(row.get(i), ','));
                }
                out.append("\r\n");
            }
            return out.toString();
        }

        static String escape(String value, char delimiter) {
            String safe = value == null ? "" : value;
            if (safe.indexOf(delimiter) >= 0 || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
                return "\"" + safe.replace("\"", "\"\"") + "\"";
            }
            return safe;
        }

        static String delimiterName(char delimiter) {
            return delimiter == '\t' ? "TAB" : "'" + delimiter + "'";
        }
    }
}
