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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class XlsxToCsvConverter implements FileConverter {
    /**
     * A leading minus is safe to preserve only when the complete value is an ordinary number.
     * Other formula trigger characters are guarded even when the suffix looks numeric because
     * spreadsheet applications may evaluate +1 and similar text as formulas when opening CSV.
     */
    private static final Pattern NEGATIVE_NUMBER = Pattern.compile(
            "-[0-9]{1,3}(?:,?[0-9]{3})*(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?%?");
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.XLSX, DocumentFormat.CSV,
            "将 XLSX 导出为 UTF-8 CSV；多工作表工作簿按表分别导出 ZIP。",
            QualityLevel.STABLE, ConversionStrategy.DATA, List.of(),
            List.of("不保留单元格样式", "公式导出已保存的缓存结果，不在服务端执行公式", "可能被表格软件解释为公式的文本值会加前导单引号保护"));

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 30);
        OoxmlPackageGuard.requireSafe(input.path(), limits);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<SheetCsv> sheets = new ArrayList<>();
        boolean[] formulas = {false};
        boolean[] guardedFormulaLikeValues = {false};
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(input.path()))) {
            boolean use1904Windowing = uses1904Windowing(workbook);
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                sheets.add(new SheetCsv(index, sheet.getSheetName(),
                        extractRows(sheet, formatter, formulas, guardedFormulaLikeValues, use1904Windowing, limits)));
            }
        }

        progress.update(TaskStage.RENDERING, 80);
        List<ConversionWarning> warnings = new ArrayList<>();
        if (formulas[0]) {
            warnings.add(ConversionWarning.of(WarningCode.FORMULA_RESULT_EXPORTED,
                    "XLSX 公式已导出工作簿中保存的缓存结果，未在服务端重新计算。", null));
        }
        if (guardedFormulaLikeValues[0]) {
            warnings.add(ConversionWarning.of(WarningCode.CSV_FORMULA_INJECTION_GUARDED,
                    "检测到可能被表格软件解释为公式的文本值，已添加前导单引号以保持文本。普通数字和负数未修改。", null));
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
                                           boolean[] guardedFormulaLikeValues,
                                           boolean use1904Windowing,
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
                    String value = cell == null ? "" : cellText(cell, formatter, formulas, use1904Windowing);
                    value = guardFormulaLikeValue(value, guardedFormulaLikeValues);
                    ConversionGuards.requireCellText(value);
                    values.add(value);
                    ConversionGuards.requireSpreadsheetCellCount(++cells, limits);
                }
            }
            rows.add(values);
        }
        return rows;
    }

    private String guardFormulaLikeValue(String value, boolean[] guarded) {
        if (value == null || value.isEmpty()) return value;
        String candidate = value.stripLeading();
        if (candidate.isEmpty()) return value;
        char first = candidate.charAt(0);
        boolean trigger = first == '=' || first == '+' || first == '@'
                || (first == '-' && !NEGATIVE_NUMBER.matcher(candidate).matches());
        if (!trigger) return value;
        guarded[0] = true;
        // Excel, LibreOffice and Numbers interpret a leading apostrophe as a text marker.
        // It is retained in raw CSV readers, so callers are warned that this is a safety guard.
        return "'" + value;
    }

    private String cellText(Cell cell, DataFormatter formatter, boolean[] formulas, boolean use1904Windowing) {
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        formulas[0] = true;
        CellType cached = cell.getCachedFormulaResultType();
        return switch (cached) {
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

    private void packageSheets(Path zipPath, List<SheetCsv> sheets, ParseLimits limits) throws Exception {
        try (LimitedZipOutput zip = LimitedZipOutput.open(zipPath, limits, "XLSX 多工作表 CSV ZIP")) {
            for (SheetCsv sheet : sheets) {
                byte[] bytes = CsvToXlsxConverter.CsvSupport.write(sheet.rows()).getBytes(StandardCharsets.UTF_8);
                zip.addBytes(String.format(Locale.ROOT, "%02d-%s.csv",
                        sheet.index() + 1, safeSheetName(sheet.name())), bytes);
            }
            zip.finish();
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
