package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.CellModel;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.ParagraphModel;
import com.fuyue.formatconverter.model.TableModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                        "复杂嵌套区域会平铺到二维单元格；跨页续表保留分页工作表并提示；仅识别安全公式，歧义值保留为文本"));
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
        for (TableContinuation continuation : detectContinuations(pages)) {
            String header = continuation.repeatedHeader() ? "检测到重复表头" : "表格列结构连续";
            warnings.add(ConversionWarning.of(WarningCode.TABLE_CONTINUATION_DETECTED,
                    "第%d页表格可能是第%d页续表（%s）；当前按页保留工作表，请人工确认是否需要合并。"
                            .formatted(continuation.toPage(), continuation.fromPage(), header),
                    continuation.toPage()));
        }
        progress.update(TaskStage.RENDERING, 75);
        writeWorkbook(pages, outputPath, limits);
        ConversionGuards.requireValidXlsxOutput(outputPath, limits, "PDF 转 XLSX");
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

    /**
     * Finds conservative cross-page table continuations without changing the
     * existing one-sheet-per-page output contract. A continuation requires
     * adjacent pages, one table per page, matching columns and table margins
     * near the page edges. Matching first-row text is reported as a repeated
     * header but is not required (some PDFs omit the header on continuation
     * pages).
     */
    static List<TableContinuation> detectContinuations(List<PageModel> pages) {
        List<TableContinuation> result = new ArrayList<>();
        for (int i = 0; i + 1 < pages.size(); i++) {
            PageModel from = pages.get(i);
            PageModel to = pages.get(i + 1);
            if (to.pageNumber() != from.pageNumber() + 1
                    || from.tables().size() != 1 || to.tables().size() != 1) continue;
            TableModel first = from.tables().get(0);
            TableModel second = to.tables().get(0);
            if (!matchingColumns(first, second)
                    || !nearPageBoundary(from, first, false)
                    || !nearPageBoundary(to, second, true)) continue;
            result.add(new TableContinuation(from.pageNumber(), to.pageNumber(),
                    sameFirstRow(first, second)));
        }
        return List.copyOf(result);
    }

    private static boolean matchingColumns(TableModel first, TableModel second) {
        if (first.columnCount() < 1 || first.columnCount() != second.columnCount()) return false;
        double firstWidth = Math.max(1d, first.box().width());
        double secondWidth = Math.max(1d, second.box().width());
        if (relativeDifference(firstWidth, secondWidth) > 0.08d) return false;
        for (int column = 0; column < first.columnCount(); column++) {
            double a = first.xGrid().get(column + 1) - first.xGrid().get(column);
            double b = second.xGrid().get(column + 1) - second.xGrid().get(column);
            if (relativeDifference(a, b) > 0.12d) return false;
        }
        return true;
    }

    private static boolean nearPageBoundary(PageModel page, TableModel table, boolean top) {
        double pageHeight = Math.max(1d, page.physicalBox().height());
        double gap = top ? table.box().y() - page.physicalBox().y()
                : page.physicalBox().bottom() - table.box().bottom();
        return gap >= -1d && gap <= pageHeight * 0.25d;
    }

    private static double relativeDifference(double first, double second) {
        return Math.abs(first - second) / Math.max(1d, Math.max(Math.abs(first), Math.abs(second)));
    }

    private static boolean sameFirstRow(TableModel first, TableModel second) {
        List<String> left = first.cells().stream().filter(cell -> cell.row() == 0)
                .sorted(Comparator.comparingInt(CellModel::column)).map(PdfToXlsxConverter::cellTextForDetection).toList();
        List<String> right = second.cells().stream().filter(cell -> cell.row() == 0)
                .sorted(Comparator.comparingInt(CellModel::column)).map(PdfToXlsxConverter::cellTextForDetection).toList();
        return !left.isEmpty() && left.equals(right);
    }

    private static String cellTextForDetection(CellModel cell) {
        return cell.paragraphs().stream().flatMap(paragraph -> paragraph.runs().stream())
                .sorted(Comparator.comparingDouble(TextBlock::baselineY).thenComparingDouble(block -> block.box().x()))
                .map(TextBlock::text).reduce("", String::concat).strip();
    }

    record TableContinuation(int fromPage, int toPage, boolean repeatedHeader) { }

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
            CellStyle integerStyle = workbook.createCellStyle();
            integerStyle.cloneStyleFrom(tableStyle);
            integerStyle.setDataFormat(workbook.createDataFormat().getFormat("0"));
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
                    cells = writeTable(sheet, table, rowOffset, tableStyle, numberStyle, integerStyle, percentStyle,
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
                            CellStyle numberStyle, CellStyle integerStyle, CellStyle percentStyle, CellStyle dateStyle,
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
            writeCellValue(target, value, style, numberStyle, integerStyle, percentStyle, dateStyle, formulaStyle);
            cells++;
            ConversionGuards.requireSpreadsheetCellCount(cells, limits);
        }
        // Some recognizers keep merge geometry in TableModel.merges while
        // emitting atomic CellModel entries.  Apply both sources, de-duping
        // ranges so POI never sees overlapping duplicate regions.
        for (CellRangeAddress region : mergedRegions(table, rowOffset)) {
            sheet.addMergedRegion(region);
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

    /**
     * Returns valid merged regions from both cell spans and the recognizer's
     * explicit merge metadata. Invalid/out-of-bounds metadata is ignored so a
     * malformed candidate cannot abort an otherwise usable workbook export.
     */
    static List<CellRangeAddress> mergedRegions(TableModel table, int rowOffset) {
        Map<String, CellRangeAddress> unique = new LinkedHashMap<>();
        for (CellModel cell : table.cells()) {
            addMerge(unique, rowOffset, table, cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan());
        }
        for (var merge : table.merges()) {
            addMerge(unique, rowOffset, table, merge.row(), merge.column(), merge.rowSpan(), merge.columnSpan());
        }
        return List.copyOf(unique.values());
    }

    private static void addMerge(Map<String, CellRangeAddress> unique, int rowOffset, TableModel table,
                                 int row, int column, int rowSpan, int columnSpan) {
        if (row < 0 || column < 0 || rowSpan < 1 || columnSpan < 1
                || rowSpan < 2 && columnSpan < 2
                || rowSpan > table.rowCount() - row || columnSpan > table.columnCount() - column) return;
        int firstRow = rowOffset + row;
        int lastRow = firstRow + rowSpan - 1;
        int lastColumn = column + columnSpan - 1;
        String key = firstRow + ":" + lastRow + ":" + column + ":" + lastColumn;
        if (unique.containsKey(key)) return;
        CellRangeAddress candidate = new CellRangeAddress(firstRow, lastRow, column, lastColumn);
        // POI rejects intersecting merged ranges. Keep the first validated
        // region and ignore later ambiguous candidates rather than failing the
        // whole workbook because of one malformed recognizer result.
        if (unique.values().stream().anyMatch(existing -> existing.intersects(candidate))) return;
        unique.put(key, candidate);
    }

    private void writeCellValue(Cell cell, String value, CellStyle textStyle, CellStyle numberStyle,
                                CellStyle integerStyle, CellStyle percentStyle, CellStyle dateStyle, CellStyle formulaStyle) {
        ParsedValue parsed = parseValue(value);
        switch (parsed.kind()) {
            case FORMULA -> {
                cell.setCellFormula(parsed.value());
                cell.setCellStyle(formulaStyle);
            }
            case NUMBER -> {
                cell.setCellValue(parsed.number());
                cell.setCellStyle(isInteger(parsed.number()) ? integerStyle : numberStyle);
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

    private boolean isInteger(double value) {
        return Double.isFinite(value) && Math.rint(value) == value;
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
                .map(this::cleanRasterGridArtifacts)
                .filter(value -> !value.isBlank()).reduce((first, second) -> first + "\n" + second).orElse("");
    }

    /** Tesseract can read a thin table rule as a pipe at the cell edge. */
    private String cleanRasterGridArtifacts(String value) {
        if (value == null || value.isBlank()) return "";
        return value.lines()
                .map(String::strip)
                .map(line -> line.replaceFirst("^\\|+\\s*", "")
                        .replaceFirst("\\s*\\|+$", ""))
                .filter(line -> !line.isBlank())
                .reduce((first, second) -> first + "\n" + second).orElse("");
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
