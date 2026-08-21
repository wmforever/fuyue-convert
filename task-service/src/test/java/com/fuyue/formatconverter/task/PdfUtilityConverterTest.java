package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfUtilityConverterTest {
    @TempDir Path temp;

    @Test
    void mergesInputsInUploadOrder() throws Exception {
        Path first = pdf("first.pdf", 1);
        Path second = pdf("second.pdf", 2);
        TaskServiceConfig config = new TaskServiceConfig(temp, 1, 2, Duration.ofSeconds(20), Duration.ofHours(1),
                ParseLimits.defaults());
        try (ConversionTaskService service = new ConversionTaskService(config, List.of(new PdfMergeInputConverter()))) {
            TaskSnapshot created = service.createTask(List.of(
                    upload("first.pdf", first), upload("second.pdf", second)), DocumentFormat.PDF_MERGED);
            TaskSnapshot complete = await(service, created.taskId());
            assertEquals(TaskStatus.SUCCESS, complete.status(), complete.errorMessage());
            try (PDDocument merged = Loader.loadPDF(service.download(created.taskId()).path().toFile())) {
                assertEquals(3, merged.getNumberOfPages());
            }
        }
    }

    @Test
    void splitsPdfIntoOneFilePerPage() throws Exception {
        Path source = pdf("source.pdf", 3);
        Path output = temp.resolve("pages.zip");
        new PdfSplitConverter().convert(input(source), temp.resolve("work"), output, ParseLimits.defaults(), (stage, progress) -> { });
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertEquals(3, zip.size());
            assertTrue(zip.getEntry("page-001.pdf") != null);
        }
    }

    @Test
    void watermarksAndOptimizesPdfWithoutChangingPageCount() throws Exception {
        Path source = pdf("source.pdf", 2);
        Path watermarked = temp.resolve("watermarked.pdf");
        Path optimized = temp.resolve("optimized.pdf");
        new PdfWatermarkConverter().convert(input(source), temp.resolve("watermark-work"), watermarked,
                ParseLimits.defaults(), (stage, progress) -> { });
        new PdfCompressConverter().convert(input(watermarked), temp.resolve("compress-work"), optimized,
                ParseLimits.defaults(), (stage, progress) -> { });
        try (PDDocument document = Loader.loadPDF(optimized.toFile())) {
            assertEquals(2, document.getNumberOfPages());
        }
    }

    @Test
    void turnsVectorGridIntoEditableWordTable() throws Exception {
        Path source = temp.resolve("grid.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.addRect(100, 600, 240, 80);
                content.moveTo(220, 600); content.lineTo(220, 680);
                content.moveTo(100, 640); content.lineTo(340, 640);
                content.stroke();
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(125, 655); content.showText("A1");
                content.newLineAtOffset(120, 0); content.showText("B1");
                content.endText();
            }
            document.save(source.toFile());
        }
        Path output = temp.resolve("grid.docx");
        new PdfToDocxConverter().convert(input(source), temp.resolve("grid-work"), output,
                ParseLimits.defaults(), (stage, progress) -> { });
        try (ZipFile zip = new ZipFile(output.toFile())) {
            String documentXml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes());
            assertTrue(documentXml.contains("<w:tbl>"));
        }
    }

    private Path pdf(String name, int pages) throws Exception {
        Path path = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pages; index++) document.addPage(new PDPage());
            document.save(path.toFile());
        }
        return path;
    }

    private UploadPayload upload(String name, Path path) throws Exception {
        byte[] data = Files.readAllBytes(path);
        return new UploadPayload(name, "application/pdf", data.length, () -> new ByteArrayInputStream(data));
    }

    private ConversionInput input(Path source) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/pdf", Files.size(source), source);
    }

    private TaskSnapshot await(ConversionTaskService service, String taskId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            TaskSnapshot snapshot = service.get(taskId);
            if (snapshot.status() == TaskStatus.SUCCESS || snapshot.status() == TaskStatus.FAILED) return snapshot;
            Thread.sleep(25);
        }
        throw new AssertionError("task did not finish");
    }
}
