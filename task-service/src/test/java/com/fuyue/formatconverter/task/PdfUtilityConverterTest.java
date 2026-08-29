package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void addsChineseWatermarkOnlyToSelectedPage() throws Exception {
        Path source = pdf("selected-pages.pdf", 3);
        Path output = temp.resolve("selected-pages-watermarked.pdf");
        ConversionOptions options = ConversionOptions.fromRequest(null, "内部资料", 0.28d, -22d,
                "bottom-right", false, "2", "#B23A30");
        new PdfWatermarkConverter().convert(input(source, options), temp.resolve("watermark-selected-work"), output,
                ParseLimits.defaults(), (stage, progress) -> { });
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1); stripper.setEndPage(1);
            assertFalse(stripper.getText(document).contains("内部资料"));
            stripper.setStartPage(2); stripper.setEndPage(2);
            assertTrue(stripper.getText(document).contains("内部资料"));
            stripper.setStartPage(3); stripper.setEndPage(3);
            assertFalse(stripper.getText(document).contains("内部资料"));
        }
    }

    @Test
    void strongCompressionReducesImageHeavyPdf() throws Exception {
        Path source = imagePdf("image-heavy.pdf", 1800, 1400);
        Path output = temp.resolve("image-heavy-optimized.pdf");
        ConversionOptions options = ConversionOptions.fromRequest("strong", null, null, null,
                null, null, null, null);
        ConversionOutput result = new PdfCompressConverter().convert(input(source, options),
                temp.resolve("compress-strong-work"), output, ParseLimits.defaults(), (stage, progress) -> { });
        assertTrue(Files.size(output) < Files.size(source), "strong compression should reduce an image-heavy PDF");
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.code().name().equals("PDF_COMPRESSION_APPLIED")));
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(1, document.getNumberOfPages());
        }
    }

    @Test
    void compressionNeverReturnsLargerFile() throws Exception {
        Path source = pdf("compact.pdf", 1);
        Path output = temp.resolve("compact-optimized.pdf");
        new PdfCompressConverter().convert(input(source), temp.resolve("compact-work"), output,
                ParseLimits.defaults(), (stage, progress) -> { });
        assertTrue(Files.size(output) <= Files.size(source));
    }

    @Test
    void encryptedPdfReturnsStableErrorCode() throws Exception {
        Path encrypted = temp.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(encrypted.toFile());
        }
        ConversionFailureException error = assertThrows(ConversionFailureException.class, () ->
                new PdfCompressConverter().convert(input(encrypted), temp.resolve("encrypted-work"),
                        temp.resolve("encrypted-output.pdf"), ParseLimits.defaults(), (stage, progress) -> { }));
        assertEquals("PDF_PASSWORD_REQUIRED", error.code());
    }

    @Test
    void signedPdfIsRejectedBeforeModification() throws Exception {
        Path signed = temp.resolve("signed.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addSignature(new PDSignature());
            document.save(signed.toFile());
        }
        ConversionFailureException error = assertThrows(ConversionFailureException.class, () ->
                new PdfWatermarkConverter().convert(input(signed), temp.resolve("signed-work"),
                        temp.resolve("signed-output.pdf"), ParseLimits.defaults(), (stage, progress) -> { }));
        assertEquals("PDF_SIGNATURE_PRESENT", error.code());
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

    private Path imagePdf(String name, int width, int height) throws Exception {
        Path path = temp.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int seed = 0x13579BDF;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                seed = seed * 1103515245 + 12345;
                int noise = (seed >>> 16) & 0xff;
                image.setRGB(x, y, (noise << 16) | (((x + noise) & 0xff) << 8) | ((y + noise) & 0xff));
            }
        }
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            var pdfImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
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

    private ConversionInput input(Path source, ConversionOptions options) throws Exception {
        return new ConversionInput(source.getFileName().toString(), "application/pdf", Files.size(source), source,
                options);
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
