package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvXlsxConverterTest {
    @TempDir Path temp;

    @Test
    void detectsGb18030SemicolonCsvAndKeepsFormulaAndDateLikeValuesAsText() throws Exception {
        Path source = temp.resolve("semicolon.csv");
        Files.write(source, "名称;日期;公式\r\n甲;2026-08-09;=2+3\r\n"
                .getBytes(Charset.forName("GB18030")));
        Path output = temp.resolve("semicolon.xlsx");

        ConversionOutput result = new CsvToXlsxConverter().convert(input(source, "text/csv"),
                temp.resolve("work"), output, ParseLimits.defaults(), (stage, percent) -> { });

        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.TEXT_ENCODING_GUESSED));
        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.CSV_DELIMITER_DETECTED));
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, row.getCell(1).getCellType());
            assertEquals("2026-08-09", row.getCell(1).getStringCellValue());
            assertEquals(CellType.STRING, row.getCell(2).getCellType());
            assertEquals("=2+3", row.getCell(2).getStringCellValue());
        }
    }

    @Test
    void rejectsMalformedQuotedCsv() {
        assertThrows(IllegalArgumentException.class,
                () -> CsvToXlsxConverter.CsvSupport.parse("a,b\n\"open,b"));
        assertThrows(IllegalArgumentException.class,
                () -> CsvToXlsxConverter.CsvSupport.parse("a,b\ninvalid\"quote,b"));
    }

    @Test
    void exportsEveryWorksheetToZipAndUsesCachedFormulaAndFormattedDateValues() throws Exception {
        Path source = temp.resolve("multi.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var data = workbook.createSheet("数据");
            Row header = data.createRow(0);
            header.createCell(0).setCellValue("日期");
            Row value = data.createRow(1);
            var date = value.createCell(0);
            date.setCellValue(LocalDateTime.of(2026, 8, 9, 0, 0));
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(dateStyle);

            var formulas = workbook.createSheet("公式结果");
            Row formulaRow = formulas.createRow(0);
            formulaRow.createCell(0).setCellValue(1);
            formulaRow.createCell(1).setCellValue(2);
            var formula = formulaRow.createCell(2);
            formula.setCellFormula("A1+B1");
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateFormulaCell(formula);
            try (var out = Files.newOutputStream(source)) { workbook.write(out); }
        }
        Path expectedCsvPath = temp.resolve("multi.csv");

        ConversionOutput result = new XlsxToCsvConverter().convert(input(source,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                temp.resolve("work"), expectedCsvPath, ParseLimits.defaults(), (stage, percent) -> { });

        assertTrue(result.outputName().endsWith("-sheets.zip"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.MULTI_SHEET_ARCHIVE));
        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.FORMULA_RESULT_EXPORTED));
        Map<String, String> entries = readZip(result.path());
        assertEquals(2, entries.size());
        String dataCsv = entries.entrySet().stream().filter(e -> e.getKey().contains("数据"))
                .findFirst().orElseThrow().getValue();
        assertTrue(dataCsv.contains("2026-08-09"), dataCsv);
        String formulaCsv = entries.entrySet().stream().filter(e -> e.getKey().contains("公式结果"))
                .findFirst().orElseThrow().getValue();
        assertTrue(formulaCsv.contains("1,2,3"), formulaCsv);
        assertFalse(formulaCsv.contains("A1+B1"), formulaCsv);
    }

    @Test
    void exportsCachedFormulaDatesUsingTheWorkbook1904DateSystem() throws Exception {
        Path source = temp.resolve("date-1904.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
            var formula = workbook.createSheet("date").createRow(0).createCell(0);
            formula.setCellFormula("0");
            formula.setCellValue(0d);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-MM-dd"));
            formula.setCellStyle(style);
            try (var output = Files.newOutputStream(source)) { workbook.write(output); }
        }

        ConversionOutput result = new XlsxToCsvConverter().convert(input(source,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                temp.resolve("date-work"), temp.resolve("date.csv"), ParseLimits.defaults(),
                (stage, percent) -> { });

        String csv = Files.readString(result.path(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("1904-01-01"), csv);
        assertFalse(csv.contains("1899-12-31"), csv);
        assertTrue(result.warnings().stream().anyMatch(w -> w.code() == WarningCode.FORMULA_RESULT_EXPORTED));
    }

    @Test
    void streamsCsvRowsBeyondTheInMemoryWindow() throws Exception {
        Path source = temp.resolve("large.csv");
        StringBuilder csv = new StringBuilder("序号,名称,值\n");
        for (int i = 0; i < 2_000; i++) csv.append(i).append(",项目").append(i).append(',').append(i * 3).append('\n');
        Files.writeString(source, csv, StandardCharsets.UTF_8);
        Path output = temp.resolve("large.xlsx");

        new CsvToXlsxConverter().convert(input(source, "text/csv"), temp.resolve("work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertEquals(2_001, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("项目1999", workbook.getSheetAt(0).getRow(2_000).getCell(1).getStringCellValue());
        }
    }

    private Map<String, String> readZip(Path path) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                try (var in = zip.getInputStream(entry)) {
                    result.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    private ConversionInput input(Path source, String type) throws Exception {
        return new ConversionInput(source.getFileName().toString(), type, Files.size(source), source);
    }
}
