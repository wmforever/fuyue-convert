package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.parser.*;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.Position;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversionTaskServiceTest {
    @TempDir Path temp;

    @Test void invalidOfdIsRejectedBeforeTaskCreation() throws Exception {
        TaskServiceConfig config = new TaskServiceConfig(temp, 1, 2, Duration.ofSeconds(5), Duration.ofHours(1), ParseLimits.defaults());
        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            byte[] invalid = "not-an-ofd".getBytes();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.createTask(List.of(new UploadPayload("bad.ofd", invalid.length,
                            () -> new ByteArrayInputStream(invalid)))));
            assertTrue(error.getMessage().contains("文件头校验失败"));
        }
    }

    @Test void convertsAllPagesIntoOneEditableDocx() throws Exception {
        Path source = temp.resolve("all-pages.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(page(210, 297, "第一页可编辑正文"));
            document.addVPage(page(297, 210, "第二页可编辑正文"));
            document.addVPage(page(148, 210, "第三页可编辑正文"));
        }
        byte[] ofd = Files.readAllBytes(source);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("tasks-data"), 1, 2,
                Duration.ofSeconds(30), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("all-pages.ofd", ofd.length,
                    () -> new ByteArrayInputStream(ofd))));
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertTrue(finished.downloadReady());
            assertEquals(3, finished.files().get(0).pageCount());
            assertEquals(DocumentFormat.OFD, finished.files().get(0).sourceFormat());
            assertEquals(DocumentFormat.DOCX, finished.files().get(0).targetFormat());

            try (XWPFDocument word = new XWPFDocument(Files.newInputStream(service.download(created.taskId()).path()))) {
                String text = word.getParagraphs().stream().map(p -> p.getText()).reduce("", String::concat);
                assertTrue(text.contains("第一页可编辑正文"));
                assertTrue(text.contains("第二页可编辑正文"));
                assertTrue(text.contains("第三页可编辑正文"));
                long sectionBreaks = word.getParagraphs().stream()
                        .filter(p -> p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr())
                        .count();
                assertEquals(2, sectionBreaks);
            }
        }
    }

    @Test void exposesRegisteredConversionRoutes() throws Exception {
        TaskServiceConfig config = new TaskServiceConfig(temp, 1, 2, Duration.ofSeconds(5), Duration.ofHours(1), ParseLimits.defaults());
        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            List<ConversionRoute> routes = service.supportedConversions();
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("ofd-to-docx") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("ofd-to-txt") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("csv-to-xlsx") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("xlsx-to-csv") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("pdf-to-docx") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("png-to-pdf") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("wps-to-docx") && route.status() == RouteStatus.PLANNED));
        }
    }

    @Test void recognizesUofExtensionFamily() {
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uof").orElseThrow());
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uot").orElseThrow());
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uos").orElseThrow());
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uop").orElseThrow());
    }

    @Test void convertsOfdIntoPlainText() throws Exception {
        Path source = temp.resolve("plain.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(page(210, 297, "可提取的文本内容"));
        }
        byte[] ofd = Files.readAllBytes(source);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("txt-data"), 1, 2,
                Duration.ofSeconds(30), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("plain.ofd", ofd.length,
                    () -> new ByteArrayInputStream(ofd))), DocumentFormat.TXT);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertEquals("plain.txt", finished.downloadName());
            assertEquals(DocumentFormat.TXT, finished.targetFormat());
            String text = Files.readString(service.download(created.taskId()).path(), StandardCharsets.UTF_8);
            assertTrue(text.contains("可提取的文本内容"));
        }
    }

    @Test void rejectsUnsupportedTargetFormat() throws Exception {
        TaskServiceConfig config = new TaskServiceConfig(temp, 1, 2, Duration.ofSeconds(5), Duration.ofHours(1), ParseLimits.defaults());
        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            byte[] data = "not-used".getBytes();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.createTask(List.of(new UploadPayload("demo.docx", data.length,
                            () -> new ByteArrayInputStream(data))), DocumentFormat.OFD));
            assertTrue(error.getMessage().contains("暂不支持"));
        }
    }

    @Test void convertsCsvIntoXlsx() throws Exception {
        byte[] csv = "姓名,分数\r张三,98\n\"李,四\",88\r\n王五,77\r".getBytes(StandardCharsets.UTF_8);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("csv-data"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("scores.csv", "text/csv", csv.length,
                    () -> new ByteArrayInputStream(csv))), DocumentFormat.XLSX);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertEquals("scores.xlsx", finished.downloadName());
            assertEquals(DocumentFormat.CSV, finished.sourceFormat());
            assertEquals(DocumentFormat.XLSX, finished.targetFormat());

            try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(service.download(created.taskId()).path()))) {
                assertEquals("姓名", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
                assertEquals("李,四", workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue());
                assertEquals("王五", workbook.getSheetAt(0).getRow(3).getCell(0).getStringCellValue());
            }
        }
    }

    @Test void rejectsUploadStreamThatExceedsDeclaredLimit() throws Exception {
        byte[] payload = "123456".getBytes(StandardCharsets.UTF_8);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("upload-limit-data"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1),
                new ParseLimits(5, 200, 100, 10, 100d, 10));

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            assertThrows(IOException.class, () -> service.createTask(List.of(new UploadPayload("note.txt", "text/plain", 5,
                    () -> new ByteArrayInputStream(payload))), DocumentFormat.PDF));
        }
    }

    @Test void failsCsvConversionWhenRowLimitIsExceeded() throws Exception {
        byte[] csv = "a,b\nc,d\n".getBytes(StandardCharsets.UTF_8);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("csv-limit-data"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1),
                new ParseLimits(1024, 1024 * 1024, 1024 * 1024, 1, 100d, 10));

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("rows.csv", "text/csv", csv.length,
                    () -> new ByteArrayInputStream(csv))), DocumentFormat.XLSX);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.FAILED, finished.status());
            assertEquals("CONVERSION_FAILED", finished.files().get(0).errorCode());
            assertTrue(finished.files().get(0).errorMessage().contains("行数超过限制"));
        }
    }

    @Test void convertsXlsxIntoCsv() throws Exception {
        Path source = temp.resolve("sheet.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Row title = workbook.createSheet("data").createRow(0);
            title.createCell(0).setCellValue("产品");
            title.createCell(1).setCellValue("数量");
            Row row = workbook.getSheetAt(0).createRow(1);
            row.createCell(0).setCellValue("A,款");
            row.createCell(1).setCellValue(12);
            try (var out = Files.newOutputStream(source)) { workbook.write(out); }
        }
        byte[] xlsx = Files.readAllBytes(source);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("xlsx-data"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("sheet.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx.length,
                    () -> new ByteArrayInputStream(xlsx))), DocumentFormat.CSV);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertEquals("sheet.csv", finished.downloadName());
            String csv = Files.readString(service.download(created.taskId()).path(), StandardCharsets.UTF_8);
            assertTrue(csv.contains("产品,数量"));
            assertTrue(csv.contains("\"A,款\",12"));
        }
    }

    @Test void convertsTextIntoPdfAndExtractsPdfText() throws Exception {
        byte[] text = "PDF 文本转换测试\nsecond line\n".getBytes(StandardCharsets.UTF_8);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("pdf-data"), 1, 4,
                Duration.ofSeconds(20), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot pdfTask = service.createTask(List.of(new UploadPayload("notes.txt", "text/plain", text.length,
                    () -> new ByteArrayInputStream(text))), DocumentFormat.PDF);
            TaskSnapshot pdfFinished = await(service, pdfTask.taskId());
            assertEquals(TaskStatus.SUCCESS, pdfFinished.status(), pdfFinished.errorMessage());
            assertEquals("notes.pdf", pdfFinished.downloadName());

            byte[] pdf = Files.readAllBytes(service.download(pdfTask.taskId()).path());
            TaskSnapshot txtTask = service.createTask(List.of(new UploadPayload("notes.pdf", "application/pdf", pdf.length,
                    () -> new ByteArrayInputStream(pdf))), DocumentFormat.TXT);
            TaskSnapshot txtFinished = await(service, txtTask.taskId());
            assertEquals(TaskStatus.SUCCESS, txtFinished.status(), txtFinished.errorMessage());
            String extracted = Files.readString(service.download(txtTask.taskId()).path(), StandardCharsets.UTF_8);
            assertTrue(extracted.contains("PDF 文本转换测试"));
        }
    }

    @Test void convertsPngIntoPdf() throws Exception {
        Path image = temp.resolve("image.png");
        BufferedImage bitmap = new BufferedImage(80, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bitmap.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 80, 50);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(10, 10, 60, 30);
        graphics.dispose();
        ImageIO.write(bitmap, "png", image.toFile());
        byte[] png = Files.readAllBytes(image);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("image-data"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("image.png", "image/png", png.length,
                    () -> new ByteArrayInputStream(png))), DocumentFormat.PDF);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, finished.status(), finished.errorMessage());
            assertEquals("image.pdf", finished.downloadName());
            assertTrue(Files.size(service.download(created.taskId()).path()) > 0);
        }
    }

    private VirtualPage page(double width, double height, String text) {
        Paragraph paragraph = new Paragraph(text, 5d);
        paragraph.setPosition(Position.Absolute).setBox(15d, 15d, width - 30d, 15d);
        return new VirtualPage(width, height).add(paragraph);
    }

    private TaskSnapshot await(ConversionTaskService service, String id) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            TaskSnapshot current = service.get(id);
            if (current.status() == TaskStatus.SUCCESS || current.status() == TaskStatus.FAILED) return current;
            Thread.sleep(20);
        }
        fail("task did not finish");
        return null;
    }
}
