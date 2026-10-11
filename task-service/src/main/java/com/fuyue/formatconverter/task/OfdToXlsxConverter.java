package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.CellModel;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TableModel;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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

/** Exports recognized OFD grid tables as real XLSX cells and merged regions. */
public final class OfdToXlsxConverter implements FileConverter {
    private static final double MINIMUM_EXPORT_CONFIDENCE = 0.85d;
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.XLSX,
            "识别 OFD 页面中的高置信度有线表格和重复文本列无线表格，并导出为真实 XLSX 单元格、工作表和合并区域。",
            QualityLevel.EXPERIMENTAL, ConversionStrategy.DATA, List.of(),
            List.of("扫描页需要 OCR；仅识别安全公式并对无歧义数字、百分比、日期做类型推断；复杂嵌套区域按二维单元格平铺；稳定跨页续表会额外生成跨页汇总工作表"));

    public OfdToXlsxConverter(SafeOfdExtractor extractor, OfdParser parser, PageLayoutAnalyzer analyzer) {
        this.extractor = java.util.Objects.requireNonNull(extractor, "extractor");
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 15);
        DocumentModel parsed = parser.parse(extractor.extract(input.path(), workDir, limits),
                input.displayName(), limits);
        OfdContentGuards.requireImagesExtracted(parsed);
        List<Integer> ocrPages = parsed.pages().stream()
                .filter(page -> page.warnings().stream().anyMatch(warning -> warning.code() == WarningCode.OCR_REQUIRED))
                .map(PageModel::pageNumber).toList();
        if (!ocrPages.isEmpty()) {
            throw new ConversionFailureException("OCR_REQUIRED",
                    "第 " + ocrPages.stream().map(String::valueOf).reduce((a, b) -> a + "、" + b).orElse("")
                            + " 页包含扫描内容；当前未配置 OCR，无法可靠提取表格");
        }
        progress.update(TaskStage.RECOGNIZING, 45);
        List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze)
                .map(OfdToXlsxConverter::retainReliableTables).toList();
        int tableCount = pages.stream().mapToInt(page -> page.tables().size()).sum();
        if (tableCount == 0) {
            throw new ConversionFailureException("NO_TABLE_FOUND", "OFD 中未识别到可可靠导出的有线表格");
        }
        List<ConversionWarning> warnings = new ArrayList<>(parsed.warnings());
        pages.forEach(page -> warnings.addAll(page.warnings()));
        for (CrossPageTableSupport.Continuation continuation : CrossPageTableSupport.continuations(pages)) {
            String header = continuation.repeatedHeader() ? "检测到重复表头" : "表格列结构连续";
            warnings.add(ConversionWarning.of(WarningCode.TABLE_CONTINUATION_DETECTED,
                    "第%d页表格可能是第%d页续表（%s）；已保留分页工作表，并生成跨页汇总工作表供复核。"
                            .formatted(continuation.toPage(), continuation.fromPage(), header),
                    continuation.toPage()));
        }
        progress.update(TaskStage.RENDERING, 70);
        writeWorkbook(pages, CrossPageTableSupport.chains(pages), outputPath, limits);
        ConversionGuards.requireValidXlsxOutput(outputPath, limits, "OFD 转 XLSX");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), parsed.sourcePageCount(), warnings);
    }

    static PageModel retainReliableTables(PageModel page) {
        List<TableModel> reliable = page.tables().stream()
                .filter(table -> table.confidence() >= MINIMUM_EXPORT_CONFIDENCE)
                .toList();
        return new PageModel(page.pageNumber(), page.physicalBox(), page.textBlocks(), page.lines(), page.images(),
                page.paragraphs(), reliable, page.warnings());
    }

    private void writeWorkbook(List<PageModel> pages, List<CrossPageTableSupport.TableChain> chains,
                               Path output, ParseLimits limits) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle wrap = workbook.createCellStyle();
            wrap.setWrapText(true);
            CellStyle numberStyle = workbook.createCellStyle();
            numberStyle.cloneStyleFrom(wrap);
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("0.###############"));
            CellStyle integerStyle = workbook.createCellStyle();
            integerStyle.cloneStyleFrom(wrap);
            integerStyle.setDataFormat(workbook.createDataFormat().getFormat("0"));
            CellStyle percentStyle = workbook.createCellStyle();
            percentStyle.cloneStyleFrom(wrap);
            percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.cloneStyleFrom(wrap);
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle formulaStyle = workbook.createCellStyle();
            formulaStyle.cloneStyleFrom(wrap);
            long cells = 0;
            for (PageModel page : pages) {
                Sheet sheet = workbook.createSheet("第" + page.pageNumber() + "页");
                int rowOffset = 0;
                for (TableModel table : page.tables()) {
                    cells = writeTable(sheet, table, rowOffset, wrap, numberStyle, integerStyle,
                            percentStyle, dateStyle, formulaStyle, limits, cells, 0);
                    rowOffset += table.rowCount() + 2;
                }
            }
            if (!chains.isEmpty()) writeSummarySheet(workbook, chains, wrap, numberStyle, integerStyle,
                    percentStyle, dateStyle, formulaStyle, limits, cells);
            try (var stream = Files.newOutputStream(output)) { workbook.write(stream); }
        }
    }

    private void writeSummarySheet(XSSFWorkbook workbook, List<CrossPageTableSupport.TableChain> chains,
                                   CellStyle wrap, CellStyle numberStyle, CellStyle integerStyle,
                                   CellStyle percentStyle, CellStyle dateStyle, CellStyle formulaStyle,
                                   ParseLimits limits, long cells) throws Exception {
        Sheet summary = workbook.createSheet("跨页汇总");
        int rowOffset = 0;
        for (int chainIndex = 0; chainIndex < chains.size(); chainIndex++) {
            CrossPageTableSupport.TableChain chain = chains.get(chainIndex);
            Row title = summary.createRow(rowOffset++);
            title.createCell(0).setCellValue("跨页表格 " + (chainIndex + 1) + "（第" + chain.firstPage() + "-" + chain.lastPage() + "页）");
            title.getCell(0).setCellStyle(wrap);
            cells++;
            ConversionGuards.requireSpreadsheetCellCount(cells, limits);
            for (CrossPageTableSupport.TableSegment segment : chain.segments()) {
                int skippedRows = segment.repeatedHeader() ? 1 : 0;
                if (segment.table().rowCount() <= skippedRows) continue;
                cells = writeTable(summary, segment.table(), rowOffset, wrap, numberStyle, integerStyle,
                        percentStyle, dateStyle, formulaStyle, limits, cells, skippedRows);
                rowOffset += segment.table().rowCount() - skippedRows + 1;
            }
            rowOffset++;
        }
        summary.setColumnWidth(0, Math.min(255 * 256, 36 * 256));
    }

    private long writeTable(Sheet sheet, TableModel table, int rowOffset, CellStyle textStyle,
                            CellStyle numberStyle, CellStyle integerStyle, CellStyle percentStyle,
                            CellStyle dateStyle, CellStyle formulaStyle, ParseLimits limits, long cells,
                            int skippedRows) throws Exception {
        for (CellModel cell : table.cells().stream()
                .sorted(Comparator.comparingInt(CellModel::row).thenComparingInt(CellModel::column)).toList()) {
            if (cell.row() < skippedRows) continue;
            int rowIndex = rowOffset + cell.row() - skippedRows;
            ConversionGuards.requireSpreadsheetRow(rowIndex, limits);
            ConversionGuards.requireSpreadsheetColumn(cell.column());
            String value = cellText(cell);
            ConversionGuards.requireCellText(value);
            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);
            var target = row.getCell(cell.column());
            if (target == null) target = row.createCell(cell.column());
            if (!value.isBlank() || target.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING
                    || target.getStringCellValue().isBlank()) {
                writeCellValue(target, value, textStyle, numberStyle, integerStyle, percentStyle,
                        dateStyle, formulaStyle);
            }
            cells++;
            ConversionGuards.requireSpreadsheetCellCount(cells, limits);
        }
        // When a repeated header is dropped, also drop its merge geometry. The
        // source page sheets retain the exact original merges.
        if (skippedRows == 0) {
            for (CellRangeAddress region : mergedRegions(table, rowOffset)) sheet.addMergedRegion(region);
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
            sheet.setColumnWidth(column, Math.min(255 * 256, width));
        }
        return cells;
    }

    /** Applies recognizer spans and explicit merge metadata without allowing
     * malformed or overlapping regions to abort an otherwise valid workbook. */
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
        if (unique.values().stream().anyMatch(existing -> existing.intersects(candidate))) return;
        unique.put(key, candidate);
    }

    private String cellText(CellModel cell) {
        return cell.paragraphs().stream()
                .map(paragraph -> paragraph.runs().stream()
                        .sorted(Comparator.comparingDouble(block -> block.box().x() + block.textOffsetXmm()))
                        .map(TextBlock::text).reduce("", String::concat).strip())
                .filter(value -> !value.isEmpty())
                .reduce((first, second) -> first + "\n" + second).orElse("");
    }

    private void writeCellValue(Cell cell, String value, CellStyle textStyle, CellStyle numberStyle,
                                CellStyle integerStyle, CellStyle percentStyle, CellStyle dateStyle,
                                CellStyle formulaStyle) {
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

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.ofd$", "") + ".xlsx";
    }
}
