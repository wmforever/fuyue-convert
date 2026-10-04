package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LibreOfficePdfConverterTest {
    private static final String CJK_TEXT = "跨平台中文";
    @TempDir Path temp;

    @Test
    void preservesVisibleScanBackgroundAndUnrecognizedInkBehindEditableWords() throws Exception {
        var discovered = LibreOfficeConverter.discover("");
        assumeTrue(discovered.isPresent(), "LibreOffice is not installed");
        var scan = new java.awt.image.BufferedImage(3749, 2500, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = scan.createGraphics();
        for (int x = 0; x < 3749; x++) {
            int gray = 45 + 170 * x / 3748;
            graphics.setColor(new java.awt.Color(gray, gray, gray));
            graphics.drawLine(x, 0, x, 2499);
        }
        // Unrecognized colored content between word boxes must remain visible.
        graphics.setColor(java.awt.Color.RED); graphics.fillRect(1687, 1312, 375, 375);
        graphics.dispose();
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(scan, "png", bytes); scan.flush();
        byte[] original = bytes.toByteArray();
        var pageBox = new com.fuyue.formatconverter.model.Rect(0, 0, 317.5, 211.6667);
        var background = new com.fuyue.formatconverter.model.ImageBlock("scan", 1, pageBox,
                "image/png", original, "OCR_SCAN_BACKGROUND", 0);
        var words = new java.util.ArrayList<com.fuyue.formatconverter.model.TextBlock>();
        for (int line = 0; line < 8; line++) {
            var ocrWords = new java.util.ArrayList<com.fuyue.formatconverter.model.TextBlock.OcrWord>();
            for (int column = 0; column < 6; column++) {
                var box = new com.fuyue.formatconverter.model.Rect(25 + column * 25, 20 + line * 22, 23, 7);
                ocrWords.add(new com.fuyue.formatconverter.model.TextBlock.OcrWord(box, "VISIBLE", .99));
            }
            words.add(new com.fuyue.formatconverter.model.TextBlock("ocr-" + line, 1,
                    new com.fuyue.formatconverter.model.Rect(25, 20 + line * 22, 148, 7), "VISIBLE ".repeat(6).strip(),
                    27 + line * 22, new com.fuyue.formatconverter.model.FontStyle("Arial", 14, false, false, null),
                    line + 1, 0, 0, java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY, ocrWords));
        }
        var page = new com.fuyue.formatconverter.model.PageModel(1, pageBox, words,
                java.util.List.of(), java.util.List.of(background), java.util.List.of(), java.util.List.of(), java.util.List.of());
        Path source = temp.resolve("scan-overlay.docx"), output = temp.resolve("scan-overlay.pdf");
        new com.fuyue.formatconverter.docx.PoiDocxRenderer().render(
                new com.fuyue.formatconverter.model.DocumentModel("scan", "test", 1,
                        java.util.List.of(page), java.util.List.of()), source);
        try (var docx = new XWPFDocument(Files.newInputStream(source))) {
            assertArrayEquals(original, docx.getAllPictures().get(0).getData());
        }
        new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF, discovered.orElseThrow(),
                Duration.ofSeconds(45), "scan overlay regression").convert(
                input(source, DocumentFormat.DOCX), temp.resolve("scan-work"), output,
                ParseLimits.defaults(), (stage, percent) -> { });
        String qa = System.getProperty("format.converter.office.qa-directory", "");
        if (!qa.isBlank()) {
            Path directory = Path.of(qa); Files.createDirectories(directory);
            Files.copy(source, directory.resolve("scan-overlay.docx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(output, directory.resolve("scan-overlay.pdf"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(pdf).contains("VISIBLE"));
            var rendered = new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 150);
            try {
                int paper = rendered.getRGB((int) (5 * 150 / 25.4), (int) (5 * 150 / 25.4));
                assertTrue((paper & 255) < 90, "scan must be visible, not an invisible image on white paper");
                int brightTextPixels = 0;
                for (int y = (int) (20 * 150 / 25.4); y < (int) (27 * 150 / 25.4); y++) {
                    for (int x = (int) (25 * 150 / 25.4); x < (int) (48 * 150 / 25.4); x++) {
                        int pixel = rendered.getRGB(x, y);
                        if (((pixel >> 16) & 255) > 220 && ((pixel >> 8) & 255) > 220 && (pixel & 255) > 220) {
                            brightTextPixels++;
                        }
                    }
                }
                assertTrue(brightTextPixels > 20, "editable white letters must remain visibly readable on dark paper");
                int stamp = rendered.getRGB((int) (158.75 * 150 / 25.4), (int) (127 * 150 / 25.4));
                assertTrue(((stamp >> 16) & 255) > 180 && ((stamp >> 8) & 255) < 80 && (stamp & 255) < 80,
                        "unrecognized red content must survive actual Office rendering");
            } finally { rendered.flush(); }
        }
    }

    @Test
    void editedScanAmountMasksOriginalInkWhenOpenedByOffice() throws Exception {
        var discovered = LibreOfficeConverter.discover("");
        assumeTrue(discovered.isPresent(), "LibreOffice is not installed");
        var scan = new java.awt.image.BufferedImage(1000, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = scan.createGraphics();
        graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 1000, 1000);
        graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 80));
        graphics.setColor(java.awt.Color.BLACK); graphics.drawString("127.50", 200, 350);
        graphics.setColor(java.awt.Color.RED); graphics.fillRect(700, 700, 40, 40);
        graphics.dispose();
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(scan, "png", bytes); scan.flush();
        var box = new com.fuyue.formatconverter.model.Rect(0, 0, 100, 100);
        var wordBox = new com.fuyue.formatconverter.model.Rect(19, 28, 30, 8);
        var words = java.util.List.of(new com.fuyue.formatconverter.model.TextBlock.OcrWord(wordBox, "127.50", .99));
        var text = new com.fuyue.formatconverter.model.TextBlock("amount", 1, wordBox, "127.50", 36,
                new com.fuyue.formatconverter.model.FontStyle("Arial", 18, false, false, null),
                0, 0, 0, java.util.List.of(), com.fuyue.formatconverter.model.Transform2D.IDENTITY, words);
        var background = new com.fuyue.formatconverter.model.ImageBlock("scan", 1, box,
                "image/png", bytes.toByteArray(), "OCR_SCAN_BACKGROUND", 0);
        var page = new com.fuyue.formatconverter.model.PageModel(1, box, java.util.List.of(text),
                java.util.List.of(), java.util.List.of(background), java.util.List.of(), java.util.List.of(), java.util.List.of());
        Path source = temp.resolve("amount.docx"), edited = temp.resolve("edited-amount.docx"), output = temp.resolve("amount.pdf");
        new com.fuyue.formatconverter.docx.PoiDocxRenderer().render(
                new com.fuyue.formatconverter.model.DocumentModel("scan", "test", 1,
                        java.util.List.of(page), java.util.List.of()), source);
        // Change the editable run only; preserve the original image and mask geometry.
        try (var input = new java.util.zip.ZipFile(source.toFile());
             var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(edited))) {
            for (var entry : java.util.Collections.list(input.entries())) {
                byte[] data = input.getInputStream(entry).readAllBytes();
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    assertEquals(1, xml.split("127\\.50", -1).length - 1);
                    data = xml.replace("127.50", "1").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); zip.write(data); zip.closeEntry();
            }
        }
        new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF, discovered.orElseThrow(),
                Duration.ofSeconds(45), "edited amount regression").convert(input(edited, DocumentFormat.DOCX),
                temp.resolve("amount-work"), output, ParseLimits.defaults(), (stage, percent) -> { });
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals("1", new PDFTextStripper().getText(pdf).strip());
            var rendered = new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 254);
            try {
                for (int y = 285; y < 360; y++) for (int x = 320; x < 480; x++) {
                    assertEquals(0xffffff, rendered.getRGB(x, y) & 0xffffff,
                            "shorter edit must erase trailing old amount ink in actual Office output");
                }
                int stamp = rendered.getRGB(720, 720);
                assertTrue(((stamp >>> 16) & 255) >= 250 && ((stamp >>> 8) & 255) <= 5 && (stamp & 255) <= 5,
                        "unrecognized annotation outside word masks must survive Office color conversion");
            } finally { rendered.flush(); }
        }
    }

    @Test
    void convertsDocxXlsxAndPptxToPdfWithReadableCjkAndPageCounts() throws Exception {
        var discovered = LibreOfficeConverter.discover("");
        assumeTrue(discovered.isPresent(), "LibreOffice is not installed");
        Path binary = discovered.orElseThrow();
        assertTrue(LibreOfficeConverter.version(binary).isPresent());

        verifyPdf(binary, createDocx(), DocumentFormat.DOCX, "DOCX-CJK");
        verifyPdf(binary, createXlsx(), DocumentFormat.XLSX, "XLSX-CJK");
        verifyPdf(binary, createPptx(), DocumentFormat.PPTX, "PPTX-CJK");
    }

    @Test
    void exportsSelectedWideSheetWithHiddenFormulaDependenciesAndUnlimitedVerticalPages() throws Exception {
        var discovered = LibreOfficeConverter.discover("");
        assumeTrue(discovered.isPresent(), "LibreOffice is not installed");
        Path source = temp.resolve("wide.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            book.createSheet("Overview").createRow(0).createCell(0).setCellValue("EXCLUDED-OVERVIEW");
            book.createSheet("Inputs").createRow(0).createCell(0).setCellValue(42);
            book.setSheetHidden(1, true);
            var sheet = book.createSheet("宽表");
            sheet.setDefaultColumnWidth(12);
            var header = sheet.createRow(0);
            for (int col = 0; col < 20; col++) header.createCell(col).setCellValue(String.format("COL%02d", col + 1));
            var formula = sheet.createRow(1).createCell(0);
            formula.setCellFormula("Inputs!A1*2");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            sheet.getRow(1).createCell(1).setCellValue(CJK_TEXT);
            for (int row = 2; row < 260; row++) sheet.createRow(row).createCell(0).setCellValue("ROW-" + row);
            sheet.getRow(259).createCell(19).setCellValue("LAST-ROW-LAST-COLUMN");
            sheet.setRepeatingRows(new org.apache.poi.ss.util.CellRangeAddress(0, 0, -1, -1));
            sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            book.setPrintArea(2, 0, 19, 0, 259);
            try (var output = Files.newOutputStream(source)) { book.write(output); }
        }
        byte[] original = Files.readAllBytes(source);
        var converter = new LibreOfficeConverter(DocumentFormat.XLSX, DocumentFormat.PDF, discovered.orElseThrow(),
                Duration.ofSeconds(45), "wide integration");
        Path nativePdf = temp.resolve("native.pdf"), fitPdf = temp.resolve("fit.pdf");
        var nativeResult = converter.convert(new ConversionInput("wide.xlsx", DocumentFormat.XLSX.contentType(),
                Files.size(source), source, SpreadsheetPdfPreparationTest.options("3", false)),
                temp.resolve("native-work"), nativePdf, ParseLimits.defaults(), (stage, percent) -> {});
        var fitResult = converter.convert(new ConversionInput("wide.xlsx", DocumentFormat.XLSX.contentType(),
                Files.size(source), source, SpreadsheetPdfPreparationTest.options("3", true)),
                temp.resolve("fit-work"), fitPdf, ParseLimits.defaults(), (stage, percent) -> {});
        assertTrue(fitResult.pageCount() > 1, "long sheet must retain vertical pagination");
        assertTrue(fitResult.pageCount() < nativeResult.pageCount(), "fit width must reduce horizontal pages");
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var pdf = Loader.loadPDF(fitPdf.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertFalse(text.contains("EXCLUDED-OVERVIEW"), text);
            assertTrue(text.contains("84"), text);
            assertTrue(text.contains(CJK_TEXT), text);
            assertTrue(text.contains("LAST-ROW-LAST-COLUMN"), text);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page); stripper.setEndPage(page);
                String pageText = stripper.getText(pdf);
                assertTrue(pageText.contains("COL01") && pageText.contains("COL20"), pageText);
            }
        }
    }

    @Test
    void windowsVersionProbeUsesConsoleLauncherWithoutChangingConversionBinary() throws Exception {
        Path exe = temp.resolve("soffice.exe"), console = temp.resolve("soffice.com");
        Files.writeString(exe, "exe"); Files.writeString(console, "console");
        assertEquals(console, LibreOfficeConverter.versionProbeBinary(exe, true));
        assertEquals(exe, LibreOfficeConverter.versionProbeBinary(exe, false));
        Files.delete(console);
        assertEquals(exe, LibreOfficeConverter.versionProbeBinary(exe, true));
    }

    private void verifyPdf(Path binary, Path source, DocumentFormat sourceFormat, String marker) throws Exception {
        Path output = temp.resolve(marker.toLowerCase() + ".pdf");
        var converter = new LibreOfficeConverter(sourceFormat, DocumentFormat.PDF, binary,
                Duration.ofSeconds(45), "integration test");

        ConversionOutput converted = converter.convert(input(source, sourceFormat),
                temp.resolve(marker.toLowerCase() + "-work"), output, ParseLimits.defaults(),
                (stage, percent) -> { });

        assertNotNull(converted.pageCount());
        assertTrue(converted.pageCount() >= 1);
        try (var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(pdf.getNumberOfPages(), converted.pageCount());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains(marker), text);
            assertTrue(text.contains(CJK_TEXT), text);
        }
    }

    private Path createDocx() throws Exception {
        Path source = temp.resolve("office-source.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("DOCX-CJK " + CJK_TEXT);
            try (var output = Files.newOutputStream(source)) { document.write(output); }
        }
        return source;
    }

    private Path createXlsx() throws Exception {
        Path source = temp.resolve("office-source.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("跨平台").createRow(0).createCell(0)
                    .setCellValue("XLSX-CJK " + CJK_TEXT);
            try (var output = Files.newOutputStream(source)) { workbook.write(output); }
        }
        return source;
    }

    private Path createPptx() throws Exception {
        Path source = temp.resolve("office-source.pptx");
        try (XMLSlideShow slides = new XMLSlideShow()) {
            var text = slides.createSlide().createTextBox();
            text.setAnchor(new Rectangle2D.Double(40, 40, 600, 100));
            text.setText("PPTX-CJK " + CJK_TEXT);
            try (var output = Files.newOutputStream(source)) { slides.write(output); }
        }
        return source;
    }

    private ConversionInput input(Path source, DocumentFormat format) throws Exception {
        return new ConversionInput(source.getFileName().toString(), format.contentType(), Files.size(source), source);
    }
}
