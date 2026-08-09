package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.model.DocumentModel;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.parser.*;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
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
import java.io.InputStream;
import java.math.BigInteger;
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
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("ofd-to-pdf")
                    && route.status() == RouteStatus.AVAILABLE
                    && route.strategy() == ConversionStrategy.FIDELITY));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("ofd-to-png")
                    && route.status() == RouteStatus.AVAILABLE
                    && route.qualityLevel() == QualityLevel.BETA
                    && route.strategy() == ConversionStrategy.FIDELITY));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("ofd-to-jpg")
                    && route.status() == RouteStatus.AVAILABLE
                    && route.qualityLevel() == QualityLevel.BETA
                    && route.strategy() == ConversionStrategy.FIDELITY));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("csv-to-xlsx") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("xlsx-to-csv") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("pdf-to-docx") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("pdf-to-txt")
                    && route.status() == RouteStatus.AVAILABLE
                    && route.qualityLevel() == QualityLevel.BETA
                    && route.strategy() == ConversionStrategy.EXTRACTION));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("pdf-to-docx")
                    && route.qualityLevel() == QualityLevel.BETA
                    && route.strategy() == ConversionStrategy.EDITABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("pdf-to-ofd")
                    && route.status() == RouteStatus.AVAILABLE
                    && route.qualityLevel() == QualityLevel.EXPERIMENTAL
                    && route.strategy() == ConversionStrategy.FIDELITY));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("ofd-to-xlsx")
                    && route.status() == RouteStatus.AVAILABLE
                    && route.qualityLevel() == QualityLevel.EXPERIMENTAL
                    && route.strategy() == ConversionStrategy.DATA));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("csv-to-xlsx") && route.strategy() == ConversionStrategy.DATA));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("png-to-pdf") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("pdf-to-jpg") && route.status() == RouteStatus.AVAILABLE));
            assertTrue(routes.stream().anyMatch(route -> route.id().equals("wps-to-docx") && route.status() == RouteStatus.PLANNED));
        }
    }

    @Test void recognizesUofExtensionFamily() {
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uof").orElseThrow());
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uot").orElseThrow());
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uos").orElseThrow());
        assertEquals(DocumentFormat.UOF, DocumentFormat.fromFileName("sample.uop").orElseThrow());
    }

    @Test void registersUofAsDirectEditableLibreOfficeConversion() {
        FileConverter converter = DefaultConverterRegistry.create(temp.resolve("soffice"), Duration.ofSeconds(30))
                .stream().filter(candidate -> candidate.route().id().equals("uof-to-docx"))
                .findFirst().orElseThrow();

        assertInstanceOf(LibreOfficeConverter.class, converter);
        assertEquals(ConversionStrategy.COMPATIBILITY, converter.route().strategy());
        assertEquals(QualityLevel.EXPERIMENTAL, converter.route().qualityLevel());
        assertTrue(converter.route().description().contains("直接转换"));
    }

    @Test void recognizesJpegAliasAsJpgFormat() {
        assertEquals(DocumentFormat.JPG, DocumentFormat.from("jpeg").orElseThrow());
        assertEquals(DocumentFormat.JPG, DocumentFormat.from(".jpeg").orElseThrow());
        assertEquals(DocumentFormat.JPG, DocumentFormat.fromFileName("photo.jpeg").orElseThrow());
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

    @Test void convertsOfdIntoFixedLayoutPdfWithOriginalPageSizes() throws Exception {
        Path source = temp.resolve("fixed-layout.ofd");
        try (OFDDoc document = new OFDDoc(source)) {
            document.addVPage(page(120, 80, "第一页固定版式"));
            document.addVPage(page(80, 120, "第二页固定版式"));
        }
        Path output = temp.resolve("fixed-layout.pdf");

        ConversionOutput converted = new OfdToPdfConverter(new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer()).convert(
                new ConversionInput("fixed-layout.ofd", "application/ofd", Files.size(source), source),
                temp.resolve("fixed-layout-work"), output, ParseLimits.defaults(), (stage, progress) -> { });

        assertEquals(2, converted.pageCount());
        assertTrue(converted.warnings().stream().anyMatch(warning -> warning.code().name().equals("FONT_SUBSTITUTED")));
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(output.toFile())) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(120d * 72d / 25.4d, pdf.getPage(0).getMediaBox().getWidth(), 0.02d);
            assertEquals(80d * 72d / 25.4d, pdf.getPage(0).getMediaBox().getHeight(), 0.02d);
            assertEquals(80d * 72d / 25.4d, pdf.getPage(1).getMediaBox().getWidth(), 0.02d);
            assertEquals(120d * 72d / 25.4d, pdf.getPage(1).getMediaBox().getHeight(), 0.02d);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("第一页固定版式"), text);
            assertTrue(text.contains("第二页固定版式"), text);
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

    @Test void convertsPdfIntoJpeg() throws Exception {
        byte[] text = "PDF 转 JPEG 测试".getBytes(StandardCharsets.UTF_8);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("pdf-jpg-data"), 1, 4,
                Duration.ofSeconds(20), Duration.ofHours(1), ParseLimits.defaults());

        try (ConversionTaskService service = new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer())) {
            TaskSnapshot pdfTask = service.createTask(List.of(new UploadPayload("jpeg-source.txt", "text/plain", text.length,
                    () -> new ByteArrayInputStream(text))), DocumentFormat.PDF);
            TaskSnapshot pdfFinished = await(service, pdfTask.taskId());
            assertEquals(TaskStatus.SUCCESS, pdfFinished.status(), pdfFinished.errorMessage());

            byte[] pdf = Files.readAllBytes(service.download(pdfTask.taskId()).path());
            TaskSnapshot jpgTask = service.createTask(List.of(new UploadPayload("jpeg-source.pdf", "application/pdf", pdf.length,
                    () -> new ByteArrayInputStream(pdf))), DocumentFormat.JPG);
            TaskSnapshot jpgFinished = await(service, jpgTask.taskId());
            assertEquals(TaskStatus.SUCCESS, jpgFinished.status(), jpgFinished.errorMessage());
            assertEquals("jpeg-source.jpg", jpgFinished.downloadName());
            assertEquals(DocumentFormat.JPG, jpgFinished.targetFormat());
            assertNotNull(ImageIO.read(service.download(jpgTask.taskId()).path().toFile()));
        }
    }

    @Test void convertsMixedSizeTextPdfToEditableDocxWithoutPageImages() throws Exception {
        Path source = temp.resolve("mixed-pages.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font cjk = loadTestFont(pdf, "/fonts/DroidSansFallback.ttf");
            PDType0Font latin = loadTestFont(pdf, "/fonts/LiberationSans-Regular.ttf");
            addPdfTextPage(pdf, new PDRectangle(300, 400), cjk, "第一页", latin, "Editable PDF 123");
            PDPage rotated = addPdfTextPage(pdf, new PDRectangle(340, 540), cjk,
                    "第二页可编辑", latin, "Word 456");
            rotated.setCropBox(new PDRectangle(20, 20, 300, 500));
            rotated.setRotation(90);
            pdf.save(source.toFile());
        }
        Path output = temp.resolve("mixed-pages.docx");
        ConversionOutput converted = new PdfToDocxConverter().convert(
                new ConversionInput("mixed-pages.pdf", "application/pdf", Files.size(source), source),
                temp.resolve("mixed-work"), output, ParseLimits.defaults(), (stage, progress) -> { });

        assertEquals(2, converted.pageCount());
        try (XWPFDocument word = new XWPFDocument(Files.newInputStream(output))) {
            String text = word.getParagraphs().stream().map(paragraph -> paragraph.getText())
                    .reduce("", String::concat);
            assertTrue(text.contains("第一页"), text);
            assertTrue(text.contains("Editable PDF 123"), text);
            assertTrue(text.contains("第二页可编辑"), text);
            assertTrue(text.contains("Word 456"), text);
            assertTrue(word.getAllPictures().isEmpty(), "纯文字 PDF 不得生成页面图片");
            var firstSection = word.getParagraphs().stream()
                    .filter(paragraph -> paragraph.getCTP().isSetPPr()
                            && paragraph.getCTP().getPPr().isSetSectPr())
                    .findFirst().orElseThrow().getCTP().getPPr().getSectPr();
            assertNotNull(firstSection);
            assertEquals(BigInteger.valueOf(6000), firstSection.getPgSz().getW());
            assertEquals(BigInteger.valueOf(8000), firstSection.getPgSz().getH());
            assertEquals(BigInteger.valueOf(10000), word.getDocument().getBody().getSectPr().getPgSz().getW());
            assertEquals(BigInteger.valueOf(6000), word.getDocument().getBody().getSectPr().getPgSz().getH());
        }
    }

    @Test void preservesDisplayedCoordinatesForRotatedPagesAndRotatedText() throws Exception {
        Path source = temp.resolve("rotated-text.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font font = loadTestFont(pdf, "/fonts/LiberationSans-Regular.ttf");
            PDPage rotatedPage = new PDPage(new PDRectangle(300, 400));
            rotatedPage.setRotation(90);
            pdf.addPage(rotatedPage);
            try (PDPageContentStream content = new PDPageContentStream(pdf, rotatedPage)) {
                content.beginText();
                content.setFont(font, 14);
                content.newLineAtOffset(30, 350);
                content.showText("PageRotation");
                content.endText();
            }

            PDPage verticalTextPage = new PDPage(new PDRectangle(300, 400));
            pdf.addPage(verticalTextPage);
            try (PDPageContentStream content = new PDPageContentStream(pdf, verticalTextPage)) {
                content.beginText();
                content.setFont(font, 14);
                content.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2d, 60, 100));
                content.showText("TextRotation");
                content.endText();
            }
            pdf.save(source.toFile());
        }

        DocumentModel parsed = new PdfLayoutParser().parse(source, source.getFileName().toString(),
                ParseLimits.defaults());
        assertEquals(2, parsed.pages().size());
        assertEquals(400d * 25.4d / 72d, parsed.pages().get(0).physicalBox().width(), 0.01d);
        assertEquals(300d * 25.4d / 72d, parsed.pages().get(0).physicalBox().height(), 0.01d);

        TextBlock pageRotation = parsed.pages().get(0).textBlocks().stream()
                .filter(block -> block.text().contains("PageRotation")).findFirst().orElseThrow();
        assertEquals(90d, pageRotation.transform().rotationDegrees(), 0.1d);
        Rect pageRotationBounds = displayedBounds(pageRotation);
        assertEquals(350d * 25.4d / 72d, pageRotationBounds.x(), 1.5d);
        assertEquals(30d * 25.4d / 72d, pageRotationBounds.y(), 1.5d);

        TextBlock textRotation = parsed.pages().get(1).textBlocks().stream()
                .filter(block -> block.text().contains("TextRotation")).findFirst().orElseThrow();
        assertEquals(-90d, textRotation.transform().rotationDegrees(), 0.1d);
        Rect textRotationBounds = displayedBounds(textRotation);
        assertEquals(60d * 25.4d / 72d, textRotationBounds.right(), 1.5d);
        assertEquals(300d * 25.4d / 72d, textRotationBounds.bottom(), 1.5d);
    }

    @Test void rejectsPdfPagesLargerThanWordSupports() throws Exception {
        Path source = temp.resolve("oversized-page.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(20_000, 400)));
            pdf.save(source.toFile());
        }
        Path output = temp.resolve("oversized-page.docx");

        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> new PdfToDocxConverter().convert(
                        new ConversionInput("oversized-page.pdf", "application/pdf", Files.size(source), source),
                        temp.resolve("oversized-work"), output, ParseLimits.defaults(),
                        (stage, progress) -> { }));

        assertEquals("PAGE_SIZE_UNSUPPORTED", error.code());
        assertTrue(error.getMessage().contains("第 1 页"));
        assertFalse(Files.exists(output));
    }

    @Test void scannedPdfFailsWithOcrRequiredInsteadOfReturningImageDocx() throws Exception {
        Path source = temp.resolve("scanned.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            BufferedImage scan = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scan.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, scan.getWidth(), scan.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("scan", 20, 30);
            graphics.dispose();
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, scan), 40, 500, 300, 180);
            }
            pdf.save(source.toFile());
        }

        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> new PdfToDocxConverter().convert(
                new ConversionInput("scanned.pdf", "application/pdf", Files.size(source), source),
                temp.resolve("scan-work"), temp.resolve("scanned.docx"), ParseLimits.defaults(),
                (stage, progress) -> { }));

        assertEquals("OCR_REQUIRED", error.code());
        assertTrue(error.getMessage().contains("第 1 页"));
        assertFalse(Files.exists(temp.resolve("scanned.docx")));

        byte[] payload = Files.readAllBytes(source);
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("scan-task-data"), 1, 2,
                Duration.ofSeconds(10), Duration.ofHours(1), ParseLimits.defaults());
        try (ConversionTaskService service = new ConversionTaskService(config, List.of(new PdfToDocxConverter()))) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("scanned.pdf", "application/pdf",
                    payload.length, () -> new ByteArrayInputStream(payload))), DocumentFormat.DOCX);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.FAILED, finished.status());
            assertEquals("OCR_REQUIRED", finished.files().get(0).errorCode());
            assertFalse(finished.downloadReady());
        }
    }

    @Test void mixedTextAndScannedPdfFailsWithoutSilentlyDroppingTheScannedPage() throws Exception {
        Path source = temp.resolve("mixed-content.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font cjk = loadTestFont(pdf, "/fonts/DroidSansFallback.ttf");
            PDType0Font latin = loadTestFont(pdf, "/fonts/LiberationSans-Regular.ttf");
            addPdfTextPage(pdf, PDRectangle.A4, cjk, "第一页有真实文字", latin, "text layer");
            PDPage imagePage = new PDPage(PDRectangle.A4);
            pdf.addPage(imagePage);
            BufferedImage scan = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            try (PDPageContentStream content = new PDPageContentStream(pdf, imagePage)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, scan), 20, 20, 100, 100);
            }
            pdf.save(source.toFile());
        }

        ConversionFailureException error = assertThrows(ConversionFailureException.class,
                () -> new PdfToDocxConverter().convert(
                        new ConversionInput("mixed-content.pdf", "application/pdf", Files.size(source), source),
                        temp.resolve("mixed-content-work"), temp.resolve("mixed-content.docx"),
                        ParseLimits.defaults(), (stage, progress) -> { }));

        assertEquals("OCR_REQUIRED", error.code());
        assertTrue(error.getMessage().contains("第 2 页"));
    }

    @Test void marksTaskFailedWhenConverterThrowsError() throws Exception {
        FileConverter crashing = new FileConverter() {
            @Override public ConversionRoute route() {
                return ConversionRoute.of(DocumentFormat.TXT, DocumentFormat.DOCX, "test converter");
            }

            @Override
            public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                            ParseLimits limits, ConversionProgress progress) {
                throw new NoClassDefFoundError("missing-test-dependency");
            }
        };
        TaskServiceConfig config = new TaskServiceConfig(temp.resolve("crash-data"), 1, 2,
                Duration.ofSeconds(5), Duration.ofHours(1), ParseLimits.defaults());
        byte[] text = "test".getBytes(StandardCharsets.UTF_8);
        try (ConversionTaskService service = new ConversionTaskService(config, List.of(crashing))) {
            TaskSnapshot created = service.createTask(List.of(new UploadPayload("test.txt", "text/plain", text.length,
                    () -> new ByteArrayInputStream(text))), DocumentFormat.DOCX);
            TaskSnapshot finished = await(service, created.taskId());
            assertEquals(TaskStatus.FAILED, finished.status());
            assertEquals("CONVERSION_FAILED", finished.files().get(0).errorCode());
            assertTrue(finished.files().get(0).errorMessage().contains("NoClassDefFoundError"));
        }
    }

    private VirtualPage page(double width, double height, String text) {
        Paragraph paragraph = new Paragraph(text, 5d);
        paragraph.setPosition(Position.Absolute).setBox(15d, 15d, width - 30d, 15d);
        return new VirtualPage(width, height).add(paragraph);
    }

    private PDType0Font loadTestFont(PDDocument pdf, String resource) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            return PDType0Font.load(pdf, input);
        }
    }

    private PDPage addPdfTextPage(PDDocument pdf, PDRectangle size,
                                  PDType0Font cjkFont, String cjkText,
                                  PDType0Font latinFont, String latinText) throws IOException {
        PDPage page = new PDPage(size);
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(cjkFont, 14);
            content.newLineAtOffset(30, size.getHeight() - 50);
            content.showText(cjkText);
            content.endText();
            content.beginText();
            content.setFont(latinFont, 14);
            content.newLineAtOffset(150, size.getHeight() - 50);
            content.showText(latinText);
            content.endText();
        }
        return page;
    }

    private Rect displayedBounds(TextBlock block) {
        double radians = Math.toRadians(block.transform().rotationDegrees());
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double centerX = block.box().x() + block.box().width() / 2d;
        double centerY = block.box().y() + block.box().height() / 2d;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double dx : new double[]{-block.box().width() / 2d, block.box().width() / 2d}) {
            for (double dy : new double[]{-block.box().height() / 2d, block.box().height() / 2d}) {
                double x = centerX + cosine * dx - sine * dy;
                double y = centerY + sine * dx + cosine * dy;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new Rect(minX, minY, maxX - minX, maxY - minY);
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
