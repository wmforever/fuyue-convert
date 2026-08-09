package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class XlsxToCsvConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.XLSX, DocumentFormat.CSV,
            "将 XLSX 导出为 UTF-8 CSV；多工作表工作簿按表分别导出 ZIP。",
            QualityLevel.STABLE, ConversionStrategy.DATA, List.of(),
            List.of("不保留单元格样式", "公式导出已保存的缓存结果，不在服务端执行公式"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<SheetCsv> sheets = new ArrayList<>();
        boolean[] formulas = {false};
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(input.path()))) {
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                sheets.add(new SheetCsv(index, sheet.getSheetName(), extractRows(sheet, formatter, formulas, limits)));
            }
        }

        progress.update(TaskStage.RENDERING, 80);
        List<ConversionWarning> warnings = new ArrayList<>();
        if (formulas[0]) {
            warnings.add(ConversionWarning.of(WarningCode.FORMULA_RESULT_EXPORTED,
                    "XLSX 公式已导出工作簿中保存的缓存结果，未在服务端重新计算。", null));
        }
        if (sheets.size() <= 1) {
            List<List<String>> rows = sheets.isEmpty() ? List.of() : sheets.get(0).rows();
            Files.writeString(outputPath, CsvToXlsxConverter.CsvSupport.write(rows), StandardCharsets.UTF_8);
            ConversionGuards.requireOutputFile(outputPath, limits, "XLSX 转 CSV");
            return new ConversionOutput(outputPath, outputFileName(input.displayName()), null, warnings);
        }

        packageSheets(outputPath, sheets, limits);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "XLSX 多工作表 CSV ZIP");
        warnings.add(ConversionWarning.of(WarningCode.MULTI_SHEET_ARCHIVE,
                "工作簿包含 " + sheets.size() + " 个工作表，已分别导出为 CSV 并打包 ZIP。", null));
        return new ConversionOutput(outputPath,
                input.displayName().replaceFirst("(?i)\\.xlsx$", "-sheets.zip"), null, warnings);
    }

    private List<List<String>> extractRows(Sheet sheet, DataFormatter formatter, boolean[] formulas,
                                           ParseLimits limits) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        long cells = 0;
        int lastRow = sheet.getPhysicalNumberOfRows() == 0 ? -1 : sheet.getLastRowNum();
        for (int r = 0; r <= lastRow; r++) {
            ConversionGuards.requireSpreadsheetRow(r, limits);
            Row row = sheet.getRow(r);
            List<String> values = new ArrayList<>();
            if (row != null) {
                int lastCell = Math.max(0, row.getLastCellNum());
                for (int c = 0; c < lastCell; c++) {
                    ConversionGuards.requireSpreadsheetColumn(c);
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : cellText(cell, formatter, formulas);
                    ConversionGuards.requireCellText(value);
                    values.add(value);
                    ConversionGuards.requireSpreadsheetCellCount(++cells, limits);
                }
            }
            rows.add(values);
        }
        return rows;
    }

    private String cellText(Cell cell, DataFormatter formatter, boolean[] formulas) {
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        formulas[0] = true;
        CellType cached = cell.getCachedFormulaResultType();
        return switch (cached) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue()).toUpperCase(Locale.ROOT);
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            case BLANK, _NONE, FORMULA -> "";
        };
    }

    private void packageSheets(Path zipPath, List<SheetCsv> sheets, ParseLimits limits) throws Exception {
        long expanded = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
            for (SheetCsv sheet : sheets) {
                byte[] bytes = CsvToXlsxConverter.CsvSupport.write(sheet.rows()).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > limits.maxEntryBytes()) {
                    throw new ConversionFailureException("OUTPUT_LIMIT_EXCEEDED",
                            "工作表 CSV 超过单文件限制：" + bytes.length + " > " + limits.maxEntryBytes());
                }
                expanded += bytes.length;
                if (expanded > limits.maxExpandedBytes()) {
                    throw new ConversionFailureException("OUTPUT_LIMIT_EXCEEDED",
                            "多工作表 CSV 总大小超过限制：" + expanded + " > " + limits.maxExpandedBytes());
                }
                zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "%02d-%s.csv",
                        sheet.index() + 1, safeSheetName(sheet.name()))));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    private String safeSheetName(String value) {
        String safe = value == null ? "sheet" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (safe.isEmpty()) safe = "sheet";
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.xlsx$", "") + ".csv";
    }

    private record SheetCsv(int index, String name, List<List<String>> rows) { }
}
