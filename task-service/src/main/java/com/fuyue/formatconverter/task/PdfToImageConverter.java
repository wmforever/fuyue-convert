package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

abstract class PdfToImageConverter implements FileConverter {
    private final ConversionRoute route;
    private final DocumentFormat targetFormat;
    private final String imageFormat;
    private final String popplerFlag;
    private final Path popplerBinary;

    protected PdfToImageConverter(DocumentFormat targetFormat, String imageFormat, String popplerFlag,
                                  String description) {
        this(targetFormat, imageFormat, popplerFlag, description, discoverPoppler().orElse(null));
    }

    protected PdfToImageConverter(DocumentFormat targetFormat, String imageFormat, String popplerFlag,
                                  String description, Path popplerBinary) {
        if (targetFormat != DocumentFormat.PNG && targetFormat != DocumentFormat.JPG) {
            throw new IllegalArgumentException("PDF 渲染图片仅支持 PNG/JPEG");
        }
        this.route = ConversionRoute.of(DocumentFormat.PDF, targetFormat, description);
        this.targetFormat = targetFormat;
        this.imageFormat = imageFormat;
        this.popplerFlag = popplerFlag;
        this.popplerBinary = popplerBinary == null ? null : popplerBinary.toAbsolutePath().normalize();
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        if (popplerBinary != null) return convertWithPoppler(input, workDir, outputPath, limits, progress);
        return convertWithPdfBox(input, workDir, outputPath, limits, progress);
    }

    private ConversionOutput convertWithPoppler(ConversionInput input, Path workDir, Path outputPath,
                                                ParseLimits limits, ConversionProgress progress) throws Exception {
        int pageCount = ConversionGuards.requirePdfPageCount(input.path(), limits);
        progress.update(TaskStage.RENDERING, 30);
        Path renderDir = Files.createDirectories(workDir.resolve("poppler"));
        Path prefix = renderDir.resolve("page");
        List<String> command = List.of(popplerBinary.toString(), "-r", "160", popplerFlag,
                input.path().toString(), prefix.toString());
        ConversionGuards.runProcess(command, workDir.resolve("pdftoppm.log"), Duration.ofMinutes(2),
                "PDF 渲染 " + targetFormat.label());
        List<Path> pages = renderedPages(renderDir);
        if (pages.isEmpty()) throw new IOException("PDF 渲染 " + targetFormat.label() + " 未生成页面");
        if (pages.size() != pageCount) throw new IOException("PDF 渲染页数不一致：" + pages.size() + " != " + pageCount);
        ConversionGuards.requireTotalSize(pages, limits, "PDF 渲染 " + targetFormat.label());
        progress.update(TaskStage.PACKAGING, 90);
        if (pages.size() == 1) {
            Files.move(pages.get(0), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new ConversionOutput(outputPath, outputName(input.displayName()), 1, List.of());
        }
        return packagePages(input, outputPath, pages, pages.size());
    }

    private ConversionOutput convertWithPdfBox(ConversionInput input, Path workDir, Path outputPath,
                                               ParseLimits limits, ConversionProgress progress) throws Exception {
        progress.update(TaskStage.PARSING, 20);
        try (var document = Loader.loadPDF(input.path().toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = document.getNumberOfPages();
            if (pages <= 0) throw new IOException("PDF 没有可转换页面");
            if (pages > limits.maxPages()) throw new IOException("PDF 页数超过限制：" + pages + " > " + limits.maxPages());
            if (pages == 1) {
                progress.update(TaskStage.RENDERING, 70);
                writePage(renderer, 0, outputPath);
                ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 单页 " + targetFormat.label());
                return new ConversionOutput(outputPath, outputName(input.displayName()), 1, List.of());
            }
            List<Path> rendered = new ArrayList<>();
            long totalImageBytes = 0;
            for (int i = 0; i < pages; i++) {
                progress.update(TaskStage.RENDERING, 20 + (int) ((i + 1) * 65.0 / Math.max(1, pages)));
                Path pageImage = workDir.resolve(pageFileName(i + 1));
                writePage(renderer, i, pageImage);
                ConversionGuards.requireNonEmptyOutputFile(pageImage, limits, "PDF 单页 " + targetFormat.label());
                totalImageBytes += Files.size(pageImage);
                if (totalImageBytes > limits.maxExpandedBytes()) {
                    throw new IOException("PDF 渲染 " + targetFormat.label() + " 总输出超过限制：" +
                            totalImageBytes + " > " + limits.maxExpandedBytes());
                }
                rendered.add(pageImage);
            }
            return packagePages(input, outputPath, rendered, pages);
        }
    }

    private void writePage(PDFRenderer renderer, int pageIndex, Path outputPath) throws IOException {
        if (!ImageIO.write(renderer.renderImageWithDPI(pageIndex, 160, ImageType.RGB),
                imageFormat, outputPath.toFile())) {
            throw new IOException("当前 Java ImageIO 不支持写入 " + targetFormat.label());
        }
    }

    private ConversionOutput packagePages(ConversionInput input, Path outputPath, List<Path> pages, int pageCount)
            throws IOException {
        Path zip = outputPath.resolveSibling(outputPath.getFileName().toString()
                .replaceFirst("\\." + targetFormat.extension() + "$", ".zip"));
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (int i = 0; i < pages.size(); i++) {
                out.putNextEntry(new ZipEntry(pageFileName(i + 1)));
                Files.copy(pages.get(i), out);
                out.closeEntry();
            }
        }
        return new ConversionOutput(zip, input.displayName().replaceFirst("(?i)\\.pdf$", "-pages.zip"),
                pageCount, List.of());
    }

    private List<Path> renderedPages(Path renderDir) throws IOException {
        try (var files = Files.list(renderDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.matches("page-\\d+\\.(png|jpe?g)") &&
                                hasTargetExtension(name);
                    })
                    .sorted(Comparator.comparingInt(this::pageNumber))
                    .toList();
        }
    }

    private boolean hasTargetExtension(String name) {
        return targetFormat == DocumentFormat.JPG
                ? name.endsWith(".jpg") || name.endsWith(".jpeg")
                : name.endsWith("." + targetFormat.extension());
    }

    private String outputName(String displayName) {
        return displayName.replaceFirst("(?i)\\.pdf$", "." + targetFormat.extension());
    }

    private String pageFileName(int page) {
        return "page-%04d.%s".formatted(page, targetFormat.extension());
    }

    private static Optional<Path> discoverPoppler() {
        List<Path> candidates = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) candidates.add(Path.of(dir, "pdftoppm"));
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private int pageNumber(Path path) {
        String name = path.getFileName().toString();
        int dash = name.indexOf('-');
        int dot = name.lastIndexOf('.');
        if (dash >= 0 && dot > dash) {
            try {
                return Integer.parseInt(name.substring(dash + 1, dot));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }
}
