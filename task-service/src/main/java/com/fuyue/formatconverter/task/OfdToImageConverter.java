package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.OfdParser;
import com.fuyue.formatconverter.parser.ParseLimits;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Renders the parsed OFD fixed-layout model to one image per page. */
abstract class OfdToImageConverter implements FileConverter {
    private static final float RENDER_DPI = 160f;
    private static final float JPEG_QUALITY = 0.9f;

    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final FixedLayoutPdfRenderer fixedLayoutRenderer = new FixedLayoutPdfRenderer();
    private final ConversionRoute route;
    private final DocumentFormat targetFormat;
    private final String imageFormat;

    protected OfdToImageConverter(DocumentFormat targetFormat, String imageFormat, String description,
                                  SafeOfdExtractor extractor, OfdParser parser) {
        if (targetFormat != DocumentFormat.PNG && targetFormat != DocumentFormat.JPG) {
            throw new IllegalArgumentException("OFD 渲染图片仅支持 PNG/JPEG");
        }
        this.targetFormat = targetFormat;
        this.imageFormat = imageFormat;
        this.extractor = java.util.Objects.requireNonNull(extractor, "extractor");
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
        this.route = ConversionRoute.of(DocumentFormat.OFD, targetFormat, description,
                QualityLevel.BETA, ConversionStrategy.FIDELITY, List.of(),
                List.of("固定以 160 DPI 输出", "多页 OFD 输出 ZIP", "复杂填充、渐变、透明度和部分弧线路径仍受固定版式渲染器限制"));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        progress.update(TaskStage.PARSING, 15);
        var safe = extractor.extract(input.path(), workDir, limits);
        var parsed = parser.parse(safe, input.displayName(), limits);
        for (var page : parsed.pages()) {
            ConversionGuards.requireRenderBounds(
                    page.physicalBox().width() * 72d / 25.4d,
                    page.physicalBox().height() * 72d / 25.4d,
                    RENDER_DPI, limits);
        }

        Path intermediatePdf = workDir.resolve("ofd-fixed-layout.pdf");
        progress.update(TaskStage.RENDERING, 35);
        fixedLayoutRenderer.render(parsed, intermediatePdf);
        ConversionGuards.requireNonEmptyOutputFile(intermediatePdf, limits, "OFD 图片渲染中间 PDF");

        List<Path> pages = renderPages(intermediatePdf, parsed.pages().size(), workDir, limits, progress);
        List<ConversionWarning> warnings = new ArrayList<>(parsed.warnings());
        parsed.pages().forEach(page -> page.warnings().stream()
                .filter(warning -> warning.code() != WarningCode.OCR_REQUIRED)
                .forEach(warnings::add));
        if (parsed.pages().stream().anyMatch(page -> !page.textBlocks().isEmpty())) {
            warnings.add(ConversionWarning.of(WarningCode.FONT_SUBSTITUTED,
                    "OFD 文字使用内置字体替代后栅格化；字形宽度和少数字符可能与原文件不同。", null));
        }
        progress.update(TaskStage.PACKAGING, 90);
        if (pages.size() == 1) {
            Files.move(pages.get(0), outputPath, StandardCopyOption.REPLACE_EXISTING);
            ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "OFD 单页 " + targetFormat.label());
            return new ConversionOutput(outputPath, singleOutputName(input.displayName()), 1, warnings);
        }
        Path zip = outputPath.resolveSibling(outputPath.getFileName().toString()
                .replaceFirst("\\." + targetFormat.extension() + "$", ".zip"));
        packagePages(zip, pages);
        ConversionGuards.requireNonEmptyOutputFile(zip, limits, "OFD 多页图片 ZIP");
        return new ConversionOutput(zip, input.displayName().replaceFirst("(?i)\\.ofd$", "-pages.zip"),
                pages.size(), warnings);
    }

    private List<Path> renderPages(Path pdf, int expectedPages, Path workDir, ParseLimits limits,
                                   ConversionProgress progress) throws IOException {
        List<Path> pages = new ArrayList<>(expectedPages);
        try (var document = Loader.loadPDF(pdf.toFile())) {
            if (document.getNumberOfPages() != expectedPages) {
                throw new IOException("OFD 图片渲染页数不一致：" + document.getNumberOfPages() + " != " + expectedPages);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            for (int index = 0; index < expectedPages; index++) {
                BufferedImage image = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
                Path page = workDir.resolve(pageFileName(index + 1));
                try {
                    writeImage(image, page);
                } finally {
                    image.flush();
                }
                ConversionGuards.requireNonEmptyOutputFile(page, limits, "OFD 单页 " + targetFormat.label());
                pages.add(page);
                progress.update(TaskStage.RENDERING,
                        40 + (int) Math.round((index + 1d) * 45d / expectedPages));
            }
        }
        ConversionGuards.requireTotalSize(pages, limits, "OFD 渲染 " + targetFormat.label());
        return pages;
    }

    private void writeImage(BufferedImage image, Path output) throws IOException {
        if (targetFormat == DocumentFormat.JPG) {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) throw new IOException("当前 Java ImageIO 不支持写入 JPEG");
            ImageWriter writer = writers.next();
            ImageOutputStream imageStream = ImageIO.createImageOutputStream(output.toFile());
            if (imageStream == null) {
                writer.dispose();
                throw new IOException("无法创建 JPEG 输出流");
            }
            try (ImageOutputStream stream = imageStream) {
                writer.setOutput(stream);
                ImageWriteParam parameters = writer.getDefaultWriteParam();
                if (parameters.canWriteCompressed()) {
                    parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    parameters.setCompressionQuality(JPEG_QUALITY);
                }
                writer.write(null, new IIOImage(image, null, null), parameters);
            } finally {
                writer.dispose();
            }
            return;
        }
        if (!ImageIO.write(image, imageFormat, output.toFile())) {
            throw new IOException("当前 Java ImageIO 不支持写入 " + targetFormat.label());
        }
    }

    private void packagePages(Path zip, List<Path> pages) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (int index = 0; index < pages.size(); index++) {
                out.putNextEntry(new ZipEntry(pageFileName(index + 1)));
                Files.copy(pages.get(index), out);
                out.closeEntry();
            }
        }
    }

    private String singleOutputName(String input) {
        return input.replaceFirst("(?i)\\.ofd$", "." + targetFormat.extension());
    }

    private String pageFileName(int page) {
        return "page-%04d.%s".formatted(page, targetFormat.extension());
    }
}
