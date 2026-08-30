package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
    void splitsOnlySelectedPdfPagesAndKeepsOriginalPageNumbers() throws Exception {
        Path source = pdf("selected-source.pdf", 5);
        Path output = temp.resolve("selected-pages.zip");
        ConversionOptions options = ConversionOptions.fromRequest(null, null, null, null,
                null, null, null, null, "2,4-5");
        new PdfSplitConverter().convert(input(source, options), temp.resolve("selected-split-work"), output,
                ParseLimits.defaults(), (stage, progress) -> { });
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertEquals(3, zip.size());
            assertTrue(zip.getEntry("page-002.pdf") != null);
            assertTrue(zip.getEntry("page-004.pdf") != null);
            assertTrue(zip.getEntry("page-005.pdf") != null);
            assertTrue(zip.getEntry("page-001.pdf") == null);
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
    void keepsWatermarkSearchableOnTextHeavyPdf() throws Exception {
        String marker = "QA-WATERMARK-2026";
        Path text = temp.resolve("text-heavy.txt");
        Files.writeString(text, java.util.stream.IntStream.rangeClosed(1, 65)
                .mapToObj(index -> "PDF 可编辑验收行 " + index + " / Editable line " + index)
                .collect(java.util.stream.Collectors.joining("\n")));
        Path source = temp.resolve("text-heavy.pdf");
        new TextToPdfConverter().convert(new ConversionInput(text.getFileName().toString(), "text/plain",
                        Files.size(text), text), temp.resolve("text-pdf-work"), source,
                ParseLimits.defaults(), (stage, progress) -> { });
        Path watermarked = temp.resolve("text-heavy-watermarked.pdf");
        ConversionOptions options = ConversionOptions.fromRequest(null, marker, 0.30d, 25d,
                "center", false, "all", "#667788");
        new PdfWatermarkConverter().convert(input(source, options), temp.resolve("text-watermark-work"),
                watermarked, ParseLimits.defaults(), (stage, progress) -> { });

        try (PDDocument document = Loader.loadPDF(watermarked.toFile())) {
            String extractedText = new PDFTextStripper().getText(document);
            assertTrue(extractedText.replaceAll("\\s+", "").contains(marker), extractedText);
        }
    }

    @Test
    void tiledWatermarkPreservesRotatedCropBoxAndRenders() throws Exception {
        Path source = temp.resolve("rotated-crop.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(500, 350));
            page.setCropBox(new PDRectangle(20, 30, 440, 280));
            page.setRotation(90);
            document.addPage(page);
            document.save(source.toFile());
        }
        Path output = temp.resolve("rotated-crop-watermarked.pdf");
        ConversionOptions options = ConversionOptions.fromRequest(null, "草稿", 0.3d, 30d,
                "center", true, "all", "#777777");
        new PdfWatermarkConverter().convert(input(source, options), temp.resolve("tiled-work"), output,
                ParseLimits.defaults(), (stage, progress) -> { });
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            PDPage page = document.getPage(0);
            assertEquals(90, page.getRotation());
            assertEquals(440f, page.getCropBox().getWidth());
            assertEquals(280f, page.getCropBox().getHeight());
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            assertTrue(text.contains("草稿"));
            BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 72);
            assertTrue(nonWhitePixels(rendered) > 1000);
        }
    }

    @Test
    void rotatedPagesKeepAllCornerPositionsInTheRequestedVisualCorner() throws Exception {
        for (int rotation : List.of(90, 180, 270)) {
            for (String position : List.of("top-left", "top-right", "bottom-left", "bottom-right")) {
                String stem = "corner-" + rotation + "-" + position;
                Path source = rotatedCropPdf(stem + ".pdf", rotation);
                Path output = temp.resolve(stem + "-watermarked.pdf");
                ConversionOptions options = ConversionOptions.fromRequest(null, "MARK", 0.85d, 0d,
                        position, false, "all", "#000000");
                new PdfWatermarkConverter().convert(input(source, options), temp.resolve(stem + "-work"), output,
                        ParseLimits.defaults(), (stage, progress) -> { });

                try (PDDocument document = Loader.loadPDF(output.toFile())) {
                    BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 72);
                    InkBounds ink = inkBounds(rendered);
                    double centerX = (ink.minX() + ink.maxX()) / 2d;
                    double centerY = (ink.minY() + ink.maxY()) / 2d;
                    boolean left = position.endsWith("left");
                    boolean top = position.startsWith("top");
                    assertTrue(left ? centerX < rendered.getWidth() * .45d
                                    : centerX > rendered.getWidth() * .55d,
                            stem + " horizontal position was " + centerX + " of " + rendered.getWidth());
                    assertTrue(top ? centerY < rendered.getHeight() * .45d
                                   : centerY > rendered.getHeight() * .55d,
                            stem + " vertical position was " + centerY + " of " + rendered.getHeight());
                    assertEquals(rotation, firstTextMatrixAngle(document.getPage(0)), 0.01d,
                            stem + " must counteract the page rotation so zero degrees stays horizontal");
                }
            }
        }
    }

    @Test
    void angledCornerWatermarksStayInsideEveryRotatedVisualCropBox() throws Exception {
        for (int rotation : List.of(0, 90, 180, 270)) {
            for (double angle : List.of(-40d, 40d)) {
                for (String position : List.of("top-left", "top-right", "bottom-left", "bottom-right")) {
                    String stem = "angled-corner-" + rotation + "-" + (angle < 0 ? "negative" : "positive")
                            + "-" + position;
                    Path source = rotatedCropPdf(stem + ".pdf", rotation);
                    Path output = temp.resolve(stem + "-watermarked.pdf");
                    ConversionOptions options = ConversionOptions.fromRequest(null, "ANGLE-MARK", 0.85d, angle,
                            position, false, "all", "#000000");
                    new PdfWatermarkConverter().convert(input(source, options), temp.resolve(stem + "-work"), output,
                            ParseLimits.defaults(), (stage, progress) -> { });

                    try (PDDocument document = Loader.loadPDF(output.toFile())) {
                        BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 72);
                        InkBounds ink = inkBounds(rendered);
                        assertInkInsidePage(rendered, ink, 8, stem);

                        double centerX = (ink.minX() + ink.maxX()) / 2d;
                        double centerY = (ink.minY() + ink.maxY()) / 2d;
                        boolean left = position.endsWith("left");
                        boolean top = position.startsWith("top");
                        assertTrue(left ? centerX < rendered.getWidth() / 2d
                                        : centerX > rendered.getWidth() / 2d,
                                stem + " horizontal position was " + centerX + " of " + rendered.getWidth());
                        assertTrue(top ? centerY < rendered.getHeight() / 2d
                                       : centerY > rendered.getHeight() / 2d,
                                stem + " vertical position was " + centerY + " of " + rendered.getHeight());
                    }
                }
            }
        }
    }

    @Test
    void tiledWatermarkKeepsThreeByFourVisualGridOnEveryRotatedPage() throws Exception {
        for (int rotation : List.of(90, 180, 270)) {
            String stem = "tile-grid-" + rotation;
            Path source = rotatedCropPdf(stem + ".pdf", rotation);
            Path output = temp.resolve(stem + "-watermarked.pdf");
            ConversionOptions options = ConversionOptions.fromRequest(null, "O", 0.85d, 0d,
                    "center", true, "all", "#000000");
            new PdfWatermarkConverter().convert(input(source, options), temp.resolve(stem + "-work"), output,
                    ParseLimits.defaults(), (stage, progress) -> { });

            try (PDDocument document = Loader.loadPDF(output.toFile())) {
                PDPage page = document.getPage(0);
                assertEquals(12, textMatrixAngles(page).size());
                for (double angle : textMatrixAngles(page)) {
                    assertEquals(rotation, angle, 0.01d,
                            stem + " must keep every tiled mark visually horizontal");
                }
                BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 72);
                double cellWidth = rendered.getWidth() / 3d;
                double cellHeight = rendered.getHeight() / 4d;
                int radius = Math.max(8, (int) Math.floor(Math.min(cellWidth, cellHeight) * .18d));
                for (int row = 0; row < 4; row++) {
                    for (int column = 0; column < 3; column++) {
                        int centerX = (int) Math.round(cellWidth * (column + .5d));
                        int centerY = (int) Math.round(cellHeight * (row + .5d));
                        assertTrue(hasInkNear(rendered, centerX, centerY, radius),
                                stem + " missing visual grid mark at row=" + row + ", column=" + column);
                    }
                }
            }
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

    private Path rotatedCropPdf(String name, int rotation) throws Exception {
        Path path = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(500, 350));
            page.setCropBox(new PDRectangle(20, 30, 440, 280));
            page.setRotation(rotation);
            document.addPage(page);
            document.save(path.toFile());
        }
        return path;
    }

    private List<Double> textMatrixAngles(PDPage page) throws Exception {
        List<Object> tokens = new PDFStreamParser(page).parse();
        List<Double> angles = new ArrayList<>();
        for (int index = 6; index < tokens.size(); index++) {
            if (!(tokens.get(index) instanceof Operator operator) || !"Tm".equals(operator.getName())) continue;
            double a = ((COSNumber) tokens.get(index - 6)).floatValue();
            double b = ((COSNumber) tokens.get(index - 5)).floatValue();
            angles.add(normalizeAngle(Math.toDegrees(Math.atan2(b, a))));
        }
        return angles;
    }

    private double firstTextMatrixAngle(PDPage page) throws Exception {
        List<Double> angles = textMatrixAngles(page);
        assertFalse(angles.isEmpty(), "watermark text matrix missing");
        return angles.get(0);
    }

    private double normalizeAngle(double angle) {
        double normalized = angle % 360d;
        return normalized < 0d ? normalized + 360d : normalized;
    }

    private InkBounds inkBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (!isInk(image.getRGB(x, y))) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        assertTrue(maxX >= minX && maxY >= minY, "rendered watermark contains no visible ink");
        return new InkBounds(minX, minY, maxX, maxY);
    }

    private boolean hasInkNear(BufferedImage image, int centerX, int centerY, int radius) {
        int left = Math.max(0, centerX - radius);
        int right = Math.min(image.getWidth() - 1, centerX + radius);
        int top = Math.max(0, centerY - radius);
        int bottom = Math.min(image.getHeight() - 1, centerY + radius);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (isInk(image.getRGB(x, y))) return true;
            }
        }
        return false;
    }

    private void assertInkInsidePage(BufferedImage image, InkBounds ink, int minimumInset, String context) {
        assertTrue(ink.minX() >= minimumInset,
                context + " touched the left edge at x=" + ink.minX());
        assertTrue(ink.minY() >= minimumInset,
                context + " touched the top edge at y=" + ink.minY());
        assertTrue(ink.maxX() <= image.getWidth() - 1 - minimumInset,
                context + " touched the right edge at x=" + ink.maxX() + " of " + image.getWidth());
        assertTrue(ink.maxY() <= image.getHeight() - 1 - minimumInset,
                context + " touched the bottom edge at y=" + ink.maxY() + " of " + image.getHeight());
    }

    private boolean isInk(int rgb) {
        return (rgb & 0x00ffffff) != 0x00ffffff;
    }

    private long nonWhitePixels(BufferedImage image) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff) count++;
            }
        }
        return count;
    }

    private record InkBounds(int minX, int minY, int maxX, int maxY) { }

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
