package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.CellModel;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ParagraphModel;
import com.fuyue.formatconverter.model.TableModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exports PDF grid tables as editable XLSX cells and keeps non-table text by page. */
public final class PdfToXlsxConverter implements FileConverter {
    private static final double MINIMUM_EXPORT_CONFIDENCE = 0.85d;
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:0|[1-9]\\d{0,14})(?:\\.\\d{1,8})?%?");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("[+-]?(?:0|[1-9]\\d{0,2}(?:,\\d{3})+)(?:\\.\\d{1,8})?%?");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern CHINESE_DATE = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern SAFE_FORMULA = Pattern.compile(
            "=(?i:(SUM|AVERAGE|MIN|MAX|COUNT|COUNTA))\\(([A-Z]{1,3}\\d+(?::[A-Z]{1,3}\\d+)?)(?:,[A-Z]{1,3}\\d+(?::[A-Z]{1,3}\\d+)?)*\\)");
    private static final Pattern SAFE_ARITHMETIC_FORMULA = Pattern.compile(
            "=(?:[A-Z]{1,3}\\d+|(?:0|[1-9]\\d{0,14})(?:\\.\\d{1,8})?)(?:[+\\-*/](?:[A-Z]{1,3}\\d+|(?:0|[1-9]\\d{0,14})(?:\\.\\d{1,8})?))+");
    private final PdfLayoutParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final PdfOcrSupport ocr;
    private final ConversionRoute route;

    public PdfToXlsxConverter() {
        this(new PdfLayoutParser(), new PageLayoutAnalyzer(), null);
    }

    PdfToXlsxConverter(PdfLayoutParser parser, PageLayoutAnalyzer analyzer, PdfOcrSupport ocr) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
        this.ocr = ocr;
        this.route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.XLSX,
                ocr == null
                        ? "识别 PDF 中的高置信度有线表格和重复文本列无线表格并导出为真实 Excel 单元格、合并区域；其他正文按页保留。"
                        : "识别 PDF 中的高置信度有线表格和重复文本列无线表格并导出为真实 Excel 单元格；扫描页使用本地 "
                                + ocr.engineName() + " OCR，其他正文按页保留。",
                QualityLevel.EXPERIMENTAL, ConversionStrategy.DATA,
                ocr == null ? List.of() : List.of(ocr.engineName().toLowerCase(java.util.Locale.ROOT)),
                List.of(ocr == null ? "扫描型 PDF 需要 OCR；无线表格和复杂版式按正文保留"
                                : "OCR 表格必须人工复核；无线表格按重复文本列保守识别",
                        "复杂嵌套区域会平铺到二维单元格；仅识别安全公式，歧义值保留为文本"));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 20);
        DocumentModel parsed = ocr == null
                ? parser.parseForTextExtraction(input.path(), input.displayName(), limits)
                : parser.parseForTextExtractionOcr(input.path(), input.displayName(), limits);
        if (ocr != null) {
            parsed = ocr.recognizeMissingPagesForText(input.path(), parsed,
                    workDir.resolve("pdf-ocr"), limits, progress);
        }
        progress.update(TaskStage.RECOGNIZING, 50);
        List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze)
                .map(PdfToXlsxConverter::retainReliableTables).toList();
        List<ConversionWarning> warnings = new ArrayList<>(parsed.warnings());
        pages.forEach(page -> warnings.addAll(page.warnings()));
        progress.update(TaskStage.RENDERING, 75);
        writeWorkbook(pages, outputPath, limits);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 转 XLSX");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()),
                parsed.sourcePageCount(), warnings);
    }

    static PageModel retainReliableTables(PageModel page) {
        List<TableModel> reliable = page.tables().stream()
                .filter(table -> table.confidence() >= MINIMUM_EXPORT_CONFIDENCE)
                .toList();
        return new PageModel(page.pageNumber(), page.physicalBox(), page.textBlocks(), page.lines(), page.images(),
                page.paragraphs(), reliable, page.warnings());
    }

    private void writeWorkbook(List<PageModel> pages, Path output, ParseLimits limits) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle tableStyle = workbook.createCellStyle();
            tableStyle.setWrapText(true);
            tableStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            tableStyle.setBorderTop(BorderStyle.THIN);
            tableStyle.setBorderRight(BorderStyle.THIN);
            tableStyle.setBorderBottom(BorderStyle.THIN);
            tableStyle.setBorderLeft(BorderStyle.THIN);
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setWrapText(true);
            textStyle.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle numberStyle = workbook.createCellStyle();
            numberStyle.cloneStyleFrom(tableStyle);
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("0.###############"));
            CellStyle percentStyle = workbook.createCellStyle();
            percentStyle.cloneStyleFrom(tableStyle);
            percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.cloneStyleFrom(tableStyle);
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle formulaStyle = workbook.createCellStyle();
            formulaStyle.cloneStyleFrom(tableStyle);
            long cells = 0;
            for (PageModel page : pages) {
                Sheet sheet = workbook.createSheet("第" + page.pageNumber() + "页");
                int rowOffset = 0;
                for (TableModel table : page.tables()) {
                    cells = writeTable(sheet, table, rowOffset, tableStyle, numberStyle, percentStyle,
                            dateStyle, formulaStyle, limits, cells);
                    rowOffset += table.rowCount() + 2;
                }
                List<ParagraphModel> body = page.paragraphs().stream()
                        .sorted(Comparator.comparingDouble((ParagraphModel p) -> p.box().y())
                                .thenComparingDouble(p -> p.box().x()))
                        .toList();
                if (!body.isEmpty()) {
                    Row marker = sheet.getRow(rowOffset);
                    if (marker == null) marker = sheet.createRow(rowOffset);
                    marker.createCell(0).setCellValue(page.tables().isEmpty()
                            ? "正文（未识别为表格）" : "正文（表格外文字）");
                    marker.getCell(0).setCellStyle(textStyle);
                    cells++;
                    ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                    rowOffset++;
                    for (ParagraphModel paragraph : body) {
                        String value = paragraphText(paragraph);
                        if (value.isBlank()) continue;
                        ConversionGuards.requireCellText(value);
                        ConversionGuards.requireSpreadsheetRow(rowOffset, limits);
                        Row row = sheet.getRow(rowOffset++);
                        if (row == null) row = sheet.createRow(rowOffset - 1);
                        row.createCell(0).setCellValue(value);
                        row.getCell(0).setCellStyle(textStyle);
                        cells++;
                        ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                    }
                }
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    Row row = sheet.createRow(0);
                    row.createCell(0).setCellValue("（空白页）");
                    row.getCell(0).setCellStyle(textStyle);
                    cells++;
                    ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                }
                if (page.tables().isEmpty()) sheet.setColumnWidth(0, 36 * 256);
            }
            try (var stream = Files.newOutputStream(output)) { workbook.write(stream); }
        }
    }

    private long writeTable(Sheet sheet, TableModel table, int rowOffset, CellStyle style,
                            CellStyle numberStyle, CellStyle percentStyle, CellStyle dateStyle,
                            CellStyle formulaStyle, ParseLimits limits, long cells) throws Exception {
        for (CellModel cell : table.cells().stream()
                .sorted(Comparator.comparingInt(CellModel::row).thenComparingInt(CellModel::column)).toList()) {
            int rowIndex = rowOffset + cell.row();
            ConversionGuards.requireSpreadsheetRow(rowIndex, limits);
            ConversionGuards.requireSpreadsheetColumn(cell.column());
            String value = cellText(cell);
            ConversionGuards.requireCellText(value);
            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);
            var target = row.getCell(cell.column());
            if (target == null) target = row.createCell(cell.column());
            writeCellValue(target, value, style, numberStyle, percentStyle, dateStyle, formulaStyle);
            cells++;
            ConversionGuards.requireSpreadsheetCellCount(cells, limits);
            if (cell.rowSpan() > 1 || cell.columnSpan() > 1) {
                sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex + cell.rowSpan() - 1,
                        cell.column(), cell.column() + cell.columnSpan() - 1));
            }
        }
        for (int row = 0; row < table.rowCount(); row++) {
            Row target = sheet.getRow(rowOffset + row);
            if (target == null) target = sheet.createRow(rowOffset + row);
            double heightMm = table.yGrid().get(row + 1) - table.yGrid().get(row);
            target.setHeightInPoints((float) Math.max(1d, heightMm * 72d / 25.4d));
        }
        for (int column = 0; column < table.columnCount(); column++) {
            double widthMm = table.xGrid().get(column + 1) - table.xGrid().get(column);
            int width = (int) Math.round(Math.max(1d, widthMm / 2d) * 256d);
            sheet.setColumnWidth(column, Math.min(255 * 256, Math.max(1, width)));
        }
        return cells;
    }

    private void writeCellValue(Cell cell, String value, CellStyle textStyle, CellStyle numberStyle,
                                CellStyle percentStyle, CellStyle dateStyle, CellStyle formulaStyle) {
        ParsedValue parsed = parseValue(value);
        switch (parsed.kind()) {
            case FORMULA -> {
                cell.setCellFormula(parsed.value());
                cell.setCellStyle(formulaStyle);
            }
            case NUMBER -> {
                cell.setCellValue(parsed.number());
                cell.setCellStyle(numberStyle);
            }
            case PERCENT -> {
                cell.setCellValue(parsed.number());
                cell.setCellStyle(percentStyle);
            }
            case DATE -> {
                cell.setCellValue(java.util.Date.from(parsed.date().atStartOfDay(ZoneOffset.UTC).toInstant()));
                cell.setCellStyle(dateStyle);
            }
            case TEXT -> {
                cell.setCellValue(value);
                cell.setCellStyle(textStyle);
            }
        }
    }

    private ParsedValue parseValue(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("\n") || raw.contains("\r")) {
            return ParsedValue.text();
        }
        String value = raw.strip();
        Matcher formula = SAFE_FORMULA.matcher(value);
        Matcher arithmetic = SAFE_ARITHMETIC_FORMULA.matcher(value);
        if (formula.matches() || arithmetic.matches()) {
            return new ParsedValue(ValueKind.FORMULA, value.substring(1), 0, null);
        }
        LocalDate date = parseDate(value);
        if (date != null) return new ParsedValue(ValueKind.DATE, null, 0, date);
        boolean percent = value.endsWith("%");
        String numeric = percent ? value.substring(0, value.length() - 1) : value;
        if (!NUMBER.matcher(value).matches() && !GROUPED_NUMBER.matcher(value).matches()) return ParsedValue.text();
        if (numeric.startsWith("+") || numeric.startsWith("-")) {
            String digits = numeric.substring(1);
            if (digits.length() > 1 && digits.startsWith("0") && !digits.startsWith("0.")) return ParsedValue.text();
        } else if (numeric.length() > 1 && numeric.startsWith("0") && !numeric.startsWith("0.")) {
            return ParsedValue.text();
        }
        try {
            double number = new BigDecimal(numeric.replace(",", "")).doubleValue();
            if (!Double.isFinite(number)) return ParsedValue.text();
            return new ParsedValue(percent ? ValueKind.PERCENT : ValueKind.NUMBER, null,
                    percent ? number / 100d : number, null);
        } catch (NumberFormatException ignored) {
            return ParsedValue.text();
        }
    }

    private LocalDate parseDate(String value) {
        Matcher matcher = ISO_DATE.matcher(value);
        if (!matcher.matches()) matcher = CHINESE_DATE.matcher(value);
        if (!matcher.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String cellText(CellModel cell) {
        return cell.paragraphs().stream().map(this::paragraphText)
                .filter(value -> !value.isBlank()).reduce((first, second) -> first + "\n" + second).orElse("");
    }

    private String paragraphText(ParagraphModel paragraph) {
        return paragraph.runs().stream().sorted(Comparator.comparingDouble(TextBlock::baselineY)
                        .thenComparingDouble(t -> t.box().x()))
                .map(TextBlock::text).reduce("", String::concat).strip();
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.pdf$", ".xlsx");
    }

    private enum ValueKind { TEXT, NUMBER, PERCENT, DATE, FORMULA }
    private record ParsedValue(ValueKind kind, String value, double number, LocalDate date) {
        static ParsedValue text() { return new ParsedValue(ValueKind.TEXT, null, 0, null); }
    }
}
