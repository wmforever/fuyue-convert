package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
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
    private static final String APP_HOME_PROPERTY = "format.converter.app.home";
    private static final String APP_HOME_ENV = "FORMAT_CONVERTER_APP_HOME";
    private final ConversionRoute route;
    private final DocumentFormat targetFormat;
    private final String imageFormat;
    private final String popplerFlag;
    private final Path popplerBinary;
    private final float renderDpi;

    protected PdfToImageConverter(DocumentFormat targetFormat, String imageFormat, String popplerFlag,
                                  String description) {
        this(targetFormat, imageFormat, popplerFlag, description, discoverPoppler().orElse(null), configuredDpi());
    }

    protected PdfToImageConverter(DocumentFormat targetFormat, String imageFormat, String popplerFlag,
                                  String description, Path popplerBinary) {
        this(targetFormat, imageFormat, popplerFlag, description, popplerBinary, configuredDpi());
    }

    protected PdfToImageConverter(DocumentFormat targetFormat, String imageFormat, String popplerFlag,
                                  String description, Path popplerBinary, float renderDpi) {
        if (targetFormat != DocumentFormat.PNG && targetFormat != DocumentFormat.JPG) {
            throw new IllegalArgumentException("PDF 渲染图片仅支持 PNG/JPEG");
        }
        if (!Float.isFinite(renderDpi) || renderDpi < 36 || renderDpi > 600) {
            throw new IllegalArgumentException("PDF 图片渲染 DPI 必须在 36-600 之间");
        }
        this.route = ConversionRoute.of(DocumentFormat.PDF, targetFormat, description,
                QualityLevel.STABLE, ConversionStrategy.FIDELITY, List.of(),
                List.of("支持指定页码导出；单个选中页输出图片，多个选中页输出 ZIP，文件名保留原页码",
                        "可按任务选择 36-600 DPI；默认 160 DPI，可通过 FORMAT_CONVERTER_IMAGE_DPI 配置 36-600 DPI"));
        this.targetFormat = targetFormat;
        this.imageFormat = imageFormat;
        this.popplerFlag = popplerFlag;
        this.popplerBinary = popplerBinary == null ? null : popplerBinary.toAbsolutePath().normalize();
        this.renderDpi = renderDpi;
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        float dpi = input.options().imageDpi() == null ? renderDpi : input.options().imageDpi();
        Files.createDirectories(workDir);
        // pdftoppm cannot emit transparent PNG. PDFBox ARGB is used for PNG so an empty PDF
        // page remains transparent instead of being silently flattened to white.
        if (targetFormat == DocumentFormat.JPG && popplerBinary != null) {
            return convertWithPoppler(input, workDir, outputPath, limits, progress, dpi);
        }
        return convertWithPdfBox(input, workDir, outputPath, limits, progress, dpi);
    }

    private ConversionOutput convertWithPoppler(ConversionInput input, Path workDir, Path outputPath,
                                                ParseLimits limits, ConversionProgress progress, float dpi) throws Exception {
        RenderPlan plan = validatePdfForRender(input, limits, dpi);
        progress.update(TaskStage.RENDERING, 30);
        Path renderDir = Files.createDirectories(workDir.resolve("poppler"));
        Path prefix = renderDir.resolve("page");
        // Render only selected ranges, keeping native source page numbers in Poppler filenames.
        for (int index = 0; index < plan.pages().size();) {
            int first = plan.pages().get(index);
            int last = first;
            while (++index < plan.pages().size() && plan.pages().get(index) == last + 1) {
                last = plan.pages().get(index);
            }
            List<String> command = new ArrayList<>(List.of(popplerBinary.toString(), "-r",
                    formatDpi(dpi), "-cropbox", popplerFlag, "-f", Integer.toString(first),
                    "-l", Integer.toString(last)));
            if (targetFormat == DocumentFormat.JPG) command.addAll(List.of("-jpegopt", "quality=90,optimize=y"));
            command.add(input.path().toString());
            command.add(prefix.toString());
            ConversionGuards.runProcess(command, workDir.resolve("pdftoppm-" + first + ".log"), Duration.ofMinutes(2),
                    "PDF 渲染 " + targetFormat.label());
            ConversionGuards.requireTotalSize(renderedPages(renderDir), limits, "PDF 渲染 " + targetFormat.label());
            progress.update(TaskStage.RENDERING, 30 + (int) (index * 55d / plan.pages().size()));
        }
        List<Path> pages = renderedPages(renderDir);
        if (pages.isEmpty()) throw new IOException("PDF 渲染 " + targetFormat.label() + " 未生成页面");
        if (!pages.stream().map(this::pageNumber).toList().equals(plan.pages())) {
            throw new IOException("PDF 渲染结果与选中页码不一致");
        }
        ConversionGuards.requireTotalSize(pages, limits, "PDF 渲染 " + targetFormat.label());
        progress.update(TaskStage.PACKAGING, 90);
        if (pages.size() == 1) {
            Files.move(pages.get(0), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new ConversionOutput(outputPath, outputName(input.displayName(), plan), 1, List.of());
        }
        return packagePages(input, outputPath, pages, plan.pages());
    }

    private ConversionOutput convertWithPdfBox(ConversionInput input, Path workDir, Path outputPath,
                                               ParseLimits limits, ConversionProgress progress, float dpi) throws Exception {
        progress.update(TaskStage.PARSING, 20);
        RenderPlan plan = validatePdfForRender(input, limits, dpi);
        try (var document = Loader.loadPDF(input.path().toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = document.getNumberOfPages();
            if (pages <= 0) throw new IOException("PDF 没有可转换页面");
            if (pages > limits.maxPages()) throw new IOException("PDF 页数超过限制：" + pages + " > " + limits.maxPages());
            if (pages != plan.totalPages()) throw new IOException("PDF 校验与渲染页数不一致");
            if (plan.pages().size() == 1) {
                progress.update(TaskStage.RENDERING, 70);
                writePage(renderer, plan.pages().get(0) - 1, outputPath, dpi);
                ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "PDF 单页 " + targetFormat.label());
                return new ConversionOutput(outputPath, outputName(input.displayName(), plan), 1, List.of());
            }
            List<Path> rendered = new ArrayList<>();
            long totalImageBytes = 0;
            for (int i = 0; i < plan.pages().size(); i++) {
                progress.update(TaskStage.RENDERING, 20 + (int) ((i + 1) * 65.0 / plan.pages().size()));
                int sourcePage = plan.pages().get(i);
                Path pageImage = workDir.resolve(pageFileName(sourcePage));
                writePage(renderer, sourcePage - 1, pageImage, dpi);
                ConversionGuards.requireNonEmptyOutputFile(pageImage, limits, "PDF 单页 " + targetFormat.label());
                totalImageBytes += Files.size(pageImage);
                if (totalImageBytes > limits.maxExpandedBytes()) {
                    throw new IOException("PDF 渲染 " + targetFormat.label() + " 总输出超过限制：" +
                            totalImageBytes + " > " + limits.maxExpandedBytes());
                }
                rendered.add(pageImage);
            }
            return packagePages(input, outputPath, rendered, plan.pages());
        }
    }

    private void writePage(PDFRenderer renderer, int pageIndex, Path outputPath, float dpi) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi,
                targetFormat == DocumentFormat.PNG ? ImageType.ARGB : ImageType.RGB);
        try { writeImageWithDpi(image, outputPath, dpi); }
        finally { image.flush(); }
    }

    private void writeImageWithDpi(BufferedImage image, Path outputPath, float dpi) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName(imageFormat);
        if (!writers.hasNext()) throw new IOException("当前 Java ImageIO 不支持写入 " + targetFormat.label());
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(outputPath.toFile())) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            if (targetFormat == DocumentFormat.JPG && params.canWriteCompressed()) {
                params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.9f);
            }
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(image), params);
            if (targetFormat == DocumentFormat.PNG) applyPngDpi(metadata, dpi);
            else applyJpegDpi(metadata, dpi);
            writer.write(null, new IIOImage(image, null, metadata), params);
        } finally {
            writer.dispose();
        }
    }

    private void applyPngDpi(IIOMetadata metadata, float dpi) throws IOException {
        String format = "javax_imageio_png_1.0";
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode physical = child(root, "pHYs");
        int pixelsPerMeter = Math.max(1, Math.round(dpi / 0.0254f));
        physical.setAttribute("pixelsPerUnitXAxis", Integer.toString(pixelsPerMeter));
        physical.setAttribute("pixelsPerUnitYAxis", Integer.toString(pixelsPerMeter));
        physical.setAttribute("unitSpecifier", "meter");
        metadata.setFromTree(format, root);
    }

    private void applyJpegDpi(IIOMetadata metadata, float dpi) throws IOException {
        String format = "javax_imageio_jpeg_image_1.0";
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode jfif = descendant(root, "app0JFIF");
        if (jfif == null) return;
        int density = Math.max(1, Math.min(65_535, Math.round(dpi)));
        jfif.setAttribute("resUnits", "1");
        jfif.setAttribute("Xdensity", Integer.toString(density));
        jfif.setAttribute("Ydensity", Integer.toString(density));
        metadata.setFromTree(format, root);
    }

    private IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (name.equals(root.item(i).getNodeName())) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode result = new IIOMetadataNode(name);
        root.appendChild(result);
        return result;
    }

    private IIOMetadataNode descendant(IIOMetadataNode node, String name) {
        if (name.equals(node.getNodeName())) return node;
        for (int i = 0; i < node.getLength(); i++) {
            IIOMetadataNode found = descendant((IIOMetadataNode) node.item(i), name);
            if (found != null) return found;
        }
        return null;
    }

    private record RenderPlan(int totalPages, List<Integer> pages) { }

    private RenderPlan validatePdfForRender(ConversionInput input, ParseLimits limits, float dpi) throws Exception {
        try (var document = Loader.loadPDF(input.path().toFile())) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) throw new IOException("PDF 没有可转换页面");
            if (pages > limits.maxPages()) throw new IOException("PDF 页数超过限制：" + pages + " > " + limits.maxPages());
            List<Integer> selected = input.options().imagePageNumbers(pages);
            for (int page : selected) {
                var pdfPage = document.getPage(page - 1);
                var crop = pdfPage.getCropBox();
                float userUnit = pdfPage.getUserUnit();
                if (!Float.isFinite(userUnit) || userUnit <= 0) userUnit = 1f;
                ConversionGuards.requireRenderBounds(crop.getWidth() * userUnit,
                        crop.getHeight() * userUnit, dpi, limits);
            }
            return new RenderPlan(pages, selected);
        } catch (InvalidPasswordException e) {
            throw new ConversionFailureException("PDF_PASSWORD_REQUIRED", "PDF 已加密，需要密码；当前任务 API 不接收密码。");
        }
    }

    private ConversionOutput packagePages(ConversionInput input, Path outputPath, List<Path> pages, List<Integer> sourcePages)
            throws IOException {
        Path zip = outputPath.resolveSibling(outputPath.getFileName().toString()
                .replaceFirst("\\." + targetFormat.extension() + "$", ".zip"));
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            for (int i = 0; i < pages.size(); i++) {
                out.putNextEntry(new ZipEntry(pageFileName(sourcePages.get(i))));
                Files.copy(pages.get(i), out);
                out.closeEntry();
            }
        }
        return new ConversionOutput(zip, input.displayName().replaceFirst("(?i)\\.pdf$", "-pages.zip"),
                sourcePages.size(), List.of());
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

    private String outputName(String displayName, RenderPlan plan) {
        String suffix = plan.totalPages() == 1 ? "." + targetFormat.extension()
                : "-" + pageFileName(plan.pages().get(0));
        return displayName.replaceFirst("(?i)\\.pdf$", suffix);
    }

    private String pageFileName(int page) {
        return "page-%04d.%s".formatted(page, targetFormat.extension());
    }

    static Optional<Path> discoverPoppler() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getenv("PDFTOPPM_BIN");
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        bundledRoot().ifPresent(root -> {
            candidates.add(root.resolve(binaryName()));
            candidates.add(root.resolve("bin").resolve(binaryName()));
        });
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) candidates.add(Path.of(dir, binaryName()));
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static Optional<Path> bundledRoot() {
        List<Path> homes = new ArrayList<>();
        addHome(homes, System.getenv(APP_HOME_ENV));
        addHome(homes, System.getProperty(APP_HOME_PROPERTY));
        addHome(homes, System.getProperty("jpackage.app-path"));
        addHome(homes, System.getProperty("user.dir"));
        for (Path home : homes) {
            Path root = home.resolve("app").resolve("poppler");
            if (Files.isDirectory(root)) return Optional.of(root.toAbsolutePath().normalize());
            root = home.resolve("poppler");
            if (Files.isDirectory(root)) return Optional.of(root.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    private static void addHome(List<Path> homes, String value) {
        if (value == null || value.isBlank()) return;
        try {
            homes.add(Path.of(value).toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            // ignore invalid paths from external environment
        }
    }

    private static String binaryName() {
        return isWindows() ? "pdftoppm.exe" : "pdftoppm";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static float configuredDpi() {
        String value = System.getenv("FORMAT_CONVERTER_IMAGE_DPI");
        if (value == null || value.isBlank()) return 160f;
        try {
            float dpi = Float.parseFloat(value.strip());
            if (!Float.isFinite(dpi) || dpi < 36 || dpi > 600) throw new NumberFormatException();
            return dpi;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("FORMAT_CONVERTER_IMAGE_DPI 必须是 36-600 之间的数字");
        }
    }

    private String formatDpi(float dpi) {
        return String.format(Locale.ROOT, "%.2f", dpi);
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
