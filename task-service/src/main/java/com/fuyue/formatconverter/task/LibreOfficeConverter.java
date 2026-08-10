package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;
import org.apache.pdfbox.Loader;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

public final class LibreOfficeConverter implements FileConverter {
    private final ConversionRoute route;
    private final Path binary;
    private final Duration timeout;
    private final String convertTo;
    private final boolean domesticFormat;

    public LibreOfficeConverter(DocumentFormat sourceFormat, DocumentFormat targetFormat,
                                Path binary, Duration timeout, String description) {
        this(sourceFormat, targetFormat, binary, timeout, description, targetFormat.extension());
    }

    public LibreOfficeConverter(DocumentFormat sourceFormat, DocumentFormat targetFormat,
                                Path binary, Duration timeout, String description, String convertTo) {
        this.route = route(sourceFormat, targetFormat, description);
        this.binary = binary.toAbsolutePath().normalize();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(2) : timeout;
        if (convertTo == null || convertTo.isBlank()) throw new IllegalArgumentException("缺少 LibreOffice 导出过滤器");
        this.convertTo = convertTo;
        this.domesticFormat = isDomestic(sourceFormat) || isDomestic(targetFormat);
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        Path outDir = Files.createTempDirectory(workDir, "office-output-");
        Path profileDir = Files.createTempDirectory(workDir, "office-profile-");
        progress.update(TaskStage.RENDERING, 25);
        List<String> command = List.of(binary.toString(), "--headless", "--nologo", "--nodefault",
                "--nofirststartwizard", "--nolockcheck",
                "-env:UserInstallation=" + profileDir.toUri(),
                "--convert-to", convertTo, "--outdir", outDir.toString(), input.path().toString());
        ConversionGuards.runProcess(command, workDir.resolve("libreoffice.log"), timeout, "LibreOffice 转换");
        progress.update(TaskStage.PACKAGING, 90);
        Path produced = findProducedFile(outDir, route.targetFormat());
        ConversionGuards.requireNonEmptyOutputFile(produced, limits, "LibreOffice");
        Integer pageCount = validateOutput(produced, route.targetFormat(), limits);
        int repairedLists = route.sourceFormat() == DocumentFormat.UOF && route.targetFormat() == DocumentFormat.DOCX
                ? UofDocxCompatibilityFixer.repairContinuedLists(produced) : 0;
        if (repairedLists > 0) pageCount = validateOutput(produced, route.targetFormat(), limits);
        Files.move(produced, outputPath, StandardCopyOption.REPLACE_EXISTING);
        List<ConversionWarning> warnings = new ArrayList<>();
        if (domesticFormat) warnings.add(ConversionWarning.of(WarningCode.OFFICE_COMPATIBILITY_LAYOUT,
                "国产格式兼容转换已完成；字体、分页、自动编号和对象位置可能因 LibreOffice 兼容性发生变化，请复核内容与版式。", null));
        if (repairedLists > 0) warnings.add(ConversionWarning.of(WarningCode.OFFICE_COMPATIBILITY_LAYOUT,
                "已修复 LibreOffice UOF 导入产生的 " + repairedLists + " 处续接列表编号。", null));
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), pageCount, warnings);
    }

    private Integer validateOutput(Path output, DocumentFormat target, ParseLimits limits) throws IOException {
        try {
            return switch (target) {
                case PDF -> {
                    try (var document = Loader.loadPDF(output.toFile())) {
                        int pages = document.getNumberOfPages();
                        if (pages <= 0) throw new IOException("LibreOffice 生成的 PDF 没有页面");
                        if (pages > limits.maxPages()) throw new IOException("LibreOffice 生成的 PDF 页数超过限制");
                        yield pages;
                    }
                }
                case DOCX -> {
                    try (var ignored = new XWPFDocument(Files.newInputStream(output))) { }
                    yield null;
                }
                case XLSX -> {
                    try (var ignored = new XSSFWorkbook(Files.newInputStream(output))) { }
                    yield null;
                }
                case PPTX -> {
                    try (var ignored = new XMLSlideShow(Files.newInputStream(output))) { }
                    yield null;
                }
                case UOF -> {
                    validateUof(output);
                    yield null;
                }
                default -> null;
            };
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("LibreOffice 生成的 " + target.label() + " 文件结构无效", e);
        }
    }

    private void validateUof(Path output) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (var input = Files.newInputStream(output)) {
            var reader = factory.createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        String namespace = reader.getNamespaceURI();
                        if (!"UOF".equals(reader.getLocalName()) || namespace == null
                                || !namespace.startsWith("http://schemas.uof.org/")) {
                            throw new IOException("LibreOffice 生成的 UOF 根元素或命名空间无效");
                        }
                        return;
                    }
                }
            } finally {
                reader.close();
            }
        }
        throw new IOException("LibreOffice 生成的 UOF 不含文档根元素");
    }

    private Path findProducedFile(Path outDir, DocumentFormat targetFormat) throws IOException {
        try (var files = Files.list(outDir)) {
            List<Path> produced = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> targetFormat.acceptsFileName(path.getFileName().toString()))
                    .toList();
            if (produced.isEmpty()) {
                throw new IOException("LibreOffice 未生成 ." + targetFormat.extension() + " 文件");
            }
            if (produced.size() != 1) {
                throw new IOException("LibreOffice 生成了多个 ." + targetFormat.extension() + " 文件");
            }
            return produced.get(0);
        }
    }

    private String outputFileName(String input) {
        String file = Paths.get(input).getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        return base + "." + route.targetFormat().extension();
    }

    private ConversionRoute route(DocumentFormat sourceFormat, DocumentFormat targetFormat, String description) {
        boolean domestic = isDomestic(sourceFormat) || isDomestic(targetFormat);
        return ConversionRoute.of(sourceFormat, targetFormat, description,
                domestic ? QualityLevel.EXPERIMENTAL : QualityLevel.BETA,
                domestic ? ConversionStrategy.COMPATIBILITY : ConversionStrategy.FIDELITY,
                List.of("libreoffice"),
                domestic ? List.of("依赖 LibreOffice 对国产格式的导入或导出兼容性") : List.of("本机字体会影响分页和版式"));
    }

    private boolean isDomestic(DocumentFormat format) {
        return format == DocumentFormat.WPS || format == DocumentFormat.ET || format == DocumentFormat.DPS ||
                format == DocumentFormat.UOF;
    }

    public static Optional<Path> discover(String configuredBinary) {
        List<Path> candidates = new ArrayList<>();
        if (configuredBinary != null && !configuredBinary.isBlank()) candidates.add(Path.of(configuredBinary));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(Path.of(dir, "soffice"));
                    candidates.add(Path.of(dir, "libreoffice"));
                }
            }
        }
        candidates.add(Path.of("/Applications/LibreOffice.app/Contents/MacOS/soffice"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate) && probe(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> version(Path binary) {
        if (binary == null || !Files.isRegularFile(binary) || !Files.isExecutable(binary)) return Optional.empty();
        try {
            Process process = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return Optional.empty();
            }
            byte[] bytes = process.getInputStream().readNBytes(2048);
            if (process.exitValue() != 0) return Optional.empty();
            String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean probe(Path binary) {
        return version(binary).isPresent();
    }
}
