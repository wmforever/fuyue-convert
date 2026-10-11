package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversionGuardsXlsxTest {
    @TempDir Path temp;

    @Test
    void acceptsAndReopensGeneratedWorkbook() throws Exception {
        Path output = temp.resolve("valid.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellValue("ok");
            try (var stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }

        assertDoesNotThrow(() -> ConversionGuards.requireValidXlsxOutput(
                output, ParseLimits.defaults(), "测试 XLSX"));
    }

    @Test
    void rejectsTruncatedOrNonXlsxOutput() throws Exception {
        Path output = temp.resolve("broken.xlsx");
        Files.writeString(output, "not an xlsx package");

        assertThrows(Exception.class, () -> ConversionGuards.requireValidXlsxOutput(
                output, ParseLimits.defaults(), "测试 XLSX"));
    }
}
