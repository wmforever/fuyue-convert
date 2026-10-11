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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exports PDF grid tables as editable XLSX cells and keeps non-table text by page. */
public final class PdfToXlsxConverter implements FileConverter {
    private static final double MINIMUM_EXPORT_CONFIDENCE = 0.85d;
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
                        "复杂嵌套区域会平铺到二维单元格；稳定跨页续表会额外生成跨页汇总工作表；仅识别安全公式，歧义值保留为文本"));
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
                    "第%d页表格可能是第%d页续表（%s）；已保留分页工作表，并生成跨页汇总工作表供复核。"
                            .formatted(continuation.toPage(), continuation.fromPage(), header),
                    continuation.toPage()));
        }
        progress.update(TaskStage.RENDERING, 75);
        writeWorkbook(pages, CrossPageTableSupport.chains(pages), outputPath, limits);
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
        return CrossPageTableSupport.continuations(pages).stream()
                .map(continuation -> new TableContinuation(continuation.fromPage(), continuation.toPage(),
                        continuation.repeatedHeader())).toList();
    }

    record TableContinuation(int fromPage, int toPage, boolean repeatedHeader) { }

    private void writeWorkbook(List<PageModel> pages, List<CrossPageTableSupport.TableChain> chains,
                               Path output, ParseLimits limits) throws Exception {
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
            if (!chains.isEmpty()) {
                writeSummarySheet(workbook, chains, tableStyle, numberStyle, integerStyle, percentStyle,
                        dateStyle, formulaStyle, textStyle, limits, cells);
            }
            try (var stream = Files.newOutputStream(output)) { workbook.write(stream); }
        }
    }

    private void writeSummarySheet(XSSFWorkbook workbook, List<CrossPageTableSupport.TableChain> chains,
                                   CellStyle tableStyle, CellStyle numberStyle, CellStyle integerStyle,
                                   CellStyle percentStyle, CellStyle dateStyle, CellStyle formulaStyle,
                                   CellStyle textStyle, ParseLimits limits, long cells) throws Exception {
        Sheet summary = workbook.createSheet("跨页汇总");
        int rowOffset = 0;
        for (int chainIndex = 0; chainIndex < chains.size(); chainIndex++) {
            CrossPageTableSupport.TableChain chain = chains.get(chainIndex);
            Row title = summary.createRow(rowOffset++);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue("跨页表格 " + (chainIndex + 1) + "（第" + chain.firstPage() + "-" + chain.lastPage() + "页）");
            titleCell.setCellStyle(textStyle);
            cells++;
            ConversionGuards.requireSpreadsheetCellCount(cells, limits);
            for (CrossPageTableSupport.TableSegment segment : chain.segments()) {
                int skippedRows = segment.repeatedHeader() ? 1 : 0;
                if (segment.table().rowCount() <= skippedRows) continue;
                cells = writeTable(summary, segment.table(), rowOffset, tableStyle, numberStyle, integerStyle,
                        percentStyle, dateStyle, formulaStyle, limits, cells, skippedRows);
                rowOffset += segment.table().rowCount() - skippedRows + 1;
            }
            rowOffset++;
        }
        summary.setColumnWidth(0, Math.min(255 * 256, 36 * 256));
    }

    private long writeTable(Sheet sheet, TableModel table, int rowOffset, CellStyle style,
                            CellStyle numberStyle, CellStyle integerStyle, CellStyle percentStyle, CellStyle dateStyle,
                            CellStyle formulaStyle, ParseLimits limits, long cells) throws Exception {
        return writeTable(sheet, table, rowOffset, style, numberStyle, integerStyle, percentStyle, dateStyle,
                formulaStyle, limits, cells, 0);
    }

    private long writeTable(Sheet sheet, TableModel table, int rowOffset, CellStyle style,
                            CellStyle numberStyle, CellStyle integerStyle, CellStyle percentStyle, CellStyle dateStyle,
                            CellStyle formulaStyle, ParseLimits limits, long cells, int skippedRows) throws Exception {
        for (CellModel cell : table.cells().stream()
                .sorted(Comparator.comparingInt(CellModel::row).thenComparingInt(CellModel::column)).toList()) {
            if (cell.row() < skippedRows) continue;
            int rowIndex = rowOffset + cell.row();
            rowIndex -= skippedRows;
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
        if (skippedRows == 0) {
            for (CellRangeAddress region : mergedRegions(table, rowOffset)) {
                sheet.addMergedRegion(region);
            }
        }
        for (int row = skippedRows; row < table.rowCount(); row++) {
            Row target = sheet.getRow(rowOffset + row - skippedRows);
            if (target == null) target = sheet.createRow(rowOffset + row - skippedRows);
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
        SpreadsheetValueParser.ParsedValue parsed = SpreadsheetValueParser.parse(value);
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

    private String cellText(CellModel cell) {
        String value = cell.paragraphs().stream().map(this::paragraphText)
                .map(this::cleanRasterGridArtifacts)
                .filter(text -> !text.isBlank()).reduce((first, second) -> first + "\n" + second).orElse("");
        return SpreadsheetValueParser.normalizeCellText(value);
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

}
