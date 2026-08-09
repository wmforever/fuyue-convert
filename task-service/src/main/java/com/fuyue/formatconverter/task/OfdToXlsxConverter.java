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
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exports recognized OFD grid tables as real XLSX cells and merged regions. */
public final class OfdToXlsxConverter implements FileConverter {
    private static final double MINIMUM_EXPORT_CONFIDENCE = 0.85d;
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.OFD, DocumentFormat.XLSX,
            "识别 OFD 页面中的有线表格并导出为真实 XLSX 单元格、工作表和合并区域。",
            QualityLevel.EXPERIMENTAL, ConversionStrategy.DATA, List.of(),
            List.of("当前仅导出置信度不低于 0.85 的水平/垂直矢量线规则表格；无线表格、公式和值类型推断尚未实现"));

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
        progress.update(TaskStage.RENDERING, 70);
        writeWorkbook(pages, outputPath, limits);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "OFD 转 XLSX");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), parsed.sourcePageCount(), warnings);
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
            CellStyle wrap = workbook.createCellStyle();
            wrap.setWrapText(true);
            long cells = 0;
            for (PageModel page : pages) {
                Sheet sheet = workbook.createSheet("第" + page.pageNumber() + "页");
                int rowOffset = 0;
                for (TableModel table : page.tables()) {
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
                        if (!value.isBlank() || target.getStringCellValue().isBlank()) target.setCellValue(value);
                        target.setCellStyle(wrap);
                        cells++;
                        ConversionGuards.requireSpreadsheetCellCount(cells, limits);
                        if (cell.rowSpan() > 1 || cell.columnSpan() > 1) {
                            sheet.addMergedRegion(new CellRangeAddress(rowIndex,
                                    rowIndex + cell.rowSpan() - 1, cell.column(),
                                    cell.column() + cell.columnSpan() - 1));
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
                        sheet.setColumnWidth(column, Math.min(255 * 256, width));
                    }
                    rowOffset += table.rowCount() + 2;
                }
            }
            try (var stream = Files.newOutputStream(output)) { workbook.write(stream); }
        }
    }

    private String cellText(CellModel cell) {
        return cell.paragraphs().stream()
                .map(paragraph -> paragraph.runs().stream()
                        .sorted(Comparator.comparingDouble(block -> block.box().x() + block.textOffsetXmm()))
                        .map(TextBlock::text).reduce("", String::concat).strip())
                .filter(value -> !value.isEmpty())
                .reduce((first, second) -> first + "\n" + second).orElse("");
    }

    private String outputFileName(String input) {
        return input.replaceFirst("(?i)\\.ofd$", "") + ".xlsx";
    }
}
