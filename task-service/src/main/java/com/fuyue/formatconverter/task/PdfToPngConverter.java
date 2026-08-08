package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PdfToPngConverter implements FileConverter {
    private final ConversionRoute route = ConversionRoute.of(DocumentFormat.PDF, DocumentFormat.PNG,
            "将 PDF 按页渲染为 PNG；多页 PDF 自动打包 ZIP。");
    private final Path popplerBinary;

    public PdfToPngConverter() {
        this(discoverPoppler().orElse(null));
    }

    public PdfToPngConverter(Path popplerBinary) {
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
        List<String> command = List.of(popplerBinary.toString(), "-r", "160", "-png",
                input.path().toString(), prefix.toString());
        ConversionGuards.runProcess(command, workDir.resolve("pdftoppm.log"), Duration.ofMinutes(2), "PDF 渲染 PNG");
        List<Path> pages;
        try (var files = Files.list(renderDir)) {
            pages = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("page-\\d+\\.png"))
                    .sorted(Comparator.comparingInt(this::pageNumber))
                    .toList();
        }
        if (pages.isEmpty()) throw new IOException("PDF 渲染 PNG 未生成页面");
        if (pages.size() != pageCount) throw new IOException("PDF 渲染页数不一致：" + pages.size() + " != " + pageCount);
        ConversionGuards.requireTotalSize(pages, limits, "PDF 渲染 PNG");
        progress.update(TaskStage.PACKAGING, 90);
        if (pages.size() == 1) {
            Files.move(pages.get(0), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", ".png"), 1, List.of());
        }
        Path zip = outputPath.resolveSibling(outputPath.getFileName().toString().replaceFirst("\\.png$", ".zip"));
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (int i = 0; i < pages.size(); i++) {
                out.putNextEntry(new ZipEntry("page-%04d.png".formatted(i + 1)));
                Files.copy(pages.get(i), out);
                out.closeEntry();
            }
        }
        return new ConversionOutput(zip, input.displayName().replaceFirst("(?i)\\.pdf$", "-pages.zip"), pages.size(), List.of());
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
                ImageIO.write(renderer.renderImageWithDPI(0, 160, ImageType.RGB), "png", outputPath.toFile());
                ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 渲染 PNG");
                return new ConversionOutput(outputPath, input.displayName().replaceFirst("(?i)\\.pdf$", ".png"), 1, List.of());
            }
            Path zip = outputPath.resolveSibling(outputPath.getFileName().toString().replaceFirst("\\.png$", ".zip"));
            try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
                long totalImageBytes = 0;
                for (int i = 0; i < pages; i++) {
                    progress.update(TaskStage.RENDERING, 20 + (int) ((i + 1) * 65.0 / Math.max(1, pages)));
                    Path pageImage = workDir.resolve("page-%04d.png".formatted(i + 1));
                    ImageIO.write(renderer.renderImageWithDPI(i, 160, ImageType.RGB), "png", pageImage.toFile());
                    ConversionGuards.requireNonEmptyOutputFile(pageImage, limits, "PDF 单页 PNG");
                    totalImageBytes += Files.size(pageImage);
                    if (totalImageBytes > limits.maxExpandedBytes()) {
                        throw new IOException("PDF 渲染 PNG 总输出超过限制：" + totalImageBytes + " > " + limits.maxExpandedBytes());
                    }
                    out.putNextEntry(new ZipEntry("page-%04d.png".formatted(i + 1)));
                    Files.copy(pageImage, out);
                    out.closeEntry();
                }
            }
            return new ConversionOutput(zip, input.displayName().replaceFirst("(?i)\\.pdf$", "-pages.zip"), pages, List.of());
        }
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
