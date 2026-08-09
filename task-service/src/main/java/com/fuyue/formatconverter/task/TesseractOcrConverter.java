package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.FontStyle;
import com.fuyue.formatconverter.model.Rect;
import com.fuyue.formatconverter.model.TextBlock;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Explicitly enabled local OCR route. No cloud service or implicit OCR fallback is used. */
public final class TesseractOcrConverter implements FileConverter {
    private static final String ENABLED_ENV = "FORMAT_CONVERTER_OCR_ENABLED";
    private static final String BINARY_ENV = "FORMAT_CONVERTER_TESSERACT_BINARY";
    private static final String LANGUAGES_ENV = "FORMAT_CONVERTER_OCR_LANGUAGES";
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)?");
    private final ConversionRoute route;
    private final Path binary;
    private final String languages;
    private final Duration timeout;

    public TesseractOcrConverter(DocumentFormat sourceFormat, Settings settings) {
        this(sourceFormat, settings.binary(), settings.languages(), Duration.ofMinutes(2));
    }

    TesseractOcrConverter(DocumentFormat sourceFormat, Path binary, String languages, Duration timeout) {
        if (sourceFormat != DocumentFormat.PNG && sourceFormat != DocumentFormat.JPG) {
            throw new IllegalArgumentException("Tesseract 图片 OCR 仅支持 PNG/JPEG");
        }
        this.binary = binary.toAbsolutePath().normalize();
        this.languages = normalizeLanguages(languages);
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMinutes(2) : timeout;
        this.route = ConversionRoute.of(sourceFormat, DocumentFormat.TXT,
                "使用显式配置的本地 Tesseract OCR 提取图片文字。",
                QualityLevel.EXPERIMENTAL, ConversionStrategy.EXTRACTION, List.of("tesseract"),
                List.of("OCR 结果可能存在错字和阅读顺序误差，必须人工复核", "不会调用云端 OCR"));
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        ConversionGuards.requireImageBounds(input.path(), limits);
        progress.update(TaskStage.PARSING, 15);
        Path base = workDir.resolve("tesseract-result");
        List<String> command = List.of(binary.toString(), input.path().toString(), base.toString(),
                "-l", languages, "--psm", "3", "txt");
        ConversionGuards.runProcess(command, workDir.resolve("tesseract.log"), timeout, "Tesseract OCR");
        Path produced = Path.of(base + ".txt");
        ConversionGuards.requireOutputFile(produced, limits, "Tesseract OCR");
        progress.update(TaskStage.PACKAGING, 90);
        Files.move(produced, outputPath, StandardCopyOption.REPLACE_EXISTING);
        return new ConversionOutput(outputPath, replaceExtension(input.displayName(), "txt"), 1,
                List.of(ConversionWarning.of(WarningCode.OCR_APPLIED,
                        "已使用本地 Tesseract（" + languages + "）执行 OCR；识别结果必须人工复核。", 1)));
    }

    List<TextBlock> recognizeLayout(Path image, Path workDir, int pageNumber,
                                    Rect physicalBox, ParseLimits limits) throws Exception {
        Files.createDirectories(workDir);
        ConversionGuards.requireImageBounds(image, limits);
        ImageDimensions dimensions = dimensions(image);
        Path base = workDir.resolve("tesseract-page-%04d".formatted(pageNumber));
        List<String> command = List.of(binary.toString(), image.toString(), base.toString(),
                "-l", languages, "--psm", "3", "tsv");
        ConversionGuards.runProcess(command, workDir.resolve("tesseract-page-%04d.log".formatted(pageNumber)),
                timeout, "Tesseract OCR 第 " + pageNumber + " 页");
        Path tsv = Path.of(base + ".tsv");
        ConversionGuards.requireOutputFile(tsv, limits, "Tesseract OCR TSV");
        return parseTsv(tsv, pageNumber, physicalBox, dimensions, limits);
    }

    private List<TextBlock> parseTsv(Path tsv, int pageNumber, Rect page,
                                     ImageDimensions dimensions, ParseLimits limits) throws Exception {
        LinkedHashMap<String, OcrLine> lines = new LinkedHashMap<>();
        try (var input = Files.lines(tsv, java.nio.charset.StandardCharsets.UTF_8)) {
            for (String row : input.skip(1).toList()) {
                String[] columns = row.split("\\t", 12);
                if (columns.length < 12 || !"5".equals(columns[0])) continue;
                String text = columns[11].strip();
                if (text.isEmpty()) continue;
                double confidence = number(columns[10], -1d);
                if (confidence < 0) continue;
                int left = integer(columns[6]);
                int top = integer(columns[7]);
                int width = integer(columns[8]);
                int height = integer(columns[9]);
                if (width <= 0 || height <= 0) continue;
                String key = columns[2] + ":" + columns[3] + ":" + columns[4];
                lines.computeIfAbsent(key, ignored -> new OcrLine()).add(text, left, top, width, height);
                if (lines.size() > limits.maxEntries()) {
                    throw new java.io.IOException("OCR 行数超过限制：" + lines.size() + " > " + limits.maxEntries());
                }
            }
        }
        List<TextBlock> result = new ArrayList<>(lines.size());
        int index = 0;
        for (OcrLine line : lines.values()) {
            double x = page.x() + line.left * page.width() / dimensions.width;
            double y = page.y() + line.top * page.height() / dimensions.height;
            double width = Math.max(0.5d, line.width() * page.width() / dimensions.width);
            double height = Math.max(0.5d, line.height() * page.height() / dimensions.height);
            double sizePt = Math.max(5d, Math.min(72d, height * 72d / 25.4d * 0.85d));
            result.add(new TextBlock("ocr-p" + pageNumber + "-l" + (++index), pageNumber,
                    new Rect(x, y, width, height), line.text(), y + height * 0.85d,
                    new FontStyle("SimSun", sizePt, false, false, null), index));
        }
        return List.copyOf(result);
    }

    private ImageDimensions dimensions(Path image) throws Exception {
        try (ImageInputStream stream = ImageIO.createImageInputStream(image.toFile())) {
            if (stream == null) throw new java.io.IOException("无法读取 OCR 图片尺寸");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new java.io.IOException("无法识别 OCR 图片格式");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return 0; }
    }

    private double number(String value, double fallback) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static Optional<Settings> configuredSettings() {
        if (!enabled(System.getenv(ENABLED_ENV))) return Optional.empty();
        Optional<Path> binary = discover(System.getenv(BINARY_ENV));
        if (binary.isEmpty()) return Optional.empty();
        String languages;
        try {
            languages = normalizeLanguages(System.getenv(LANGUAGES_ENV));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Set<String> available = languages(binary.orElseThrow());
        boolean supported = List.of(languages.split("\\+")).stream().allMatch(available::contains);
        if (!supported) return Optional.empty();
        return Optional.of(new Settings(binary.orElseThrow(), languages,
                version(binary.orElseThrow()).orElse("unknown")));
    }

    public static boolean configuredEnabled() {
        return enabled(System.getenv(ENABLED_ENV));
    }

    public static Optional<Path> discover(String configuredBinary) {
        List<Path> candidates = new ArrayList<>();
        if (configuredBinary != null && !configuredBinary.isBlank()) candidates.add(Path.of(configuredBinary));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (!directory.isBlank()) candidates.add(Path.of(directory, isWindows() ? "tesseract.exe" : "tesseract"));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).filter(Files::isExecutable)
                .filter(candidate -> version(candidate).isPresent()).findFirst();
    }

    public static Optional<String> version(Path binary) {
        return commandOutput(binary, "--version").map(value -> value.lines().findFirst().orElse(value).trim());
    }

    public static Set<String> languages(Path binary) {
        return commandOutput(binary, "--list-langs").map(value -> {
            Set<String> result = new LinkedHashSet<>();
            value.lines().map(String::trim).filter(LANGUAGE.asMatchPredicate()).forEach(result::add);
            return Set.copyOf(result);
        }).orElseGet(Set::of);
    }

    private static Optional<String> commandOutput(Path binary, String argument) {
        if (binary == null || !Files.isRegularFile(binary) || !Files.isExecutable(binary)) return Optional.empty();
        try {
            Process process = new ProcessBuilder(binary.toString(), argument).redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readNBytes(64 * 1024),
                    java.nio.charset.StandardCharsets.UTF_8);
            return process.exitValue() == 0 && !output.isBlank() ? Optional.of(output) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String normalizeLanguages(String value) {
        String configured = value == null || value.isBlank() ? "chi_sim+eng" : value.strip();
        String[] parts = configured.split("\\+");
        if (parts.length == 0) throw new IllegalArgumentException("OCR 语言不能为空");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String part : parts) {
            String language = part.strip();
            if (!LANGUAGE.matcher(language).matches()) throw new IllegalArgumentException("OCR 语言标识无效");
            normalized.add(language);
        }
        return String.join("+", normalized);
    }

    private static boolean enabled(String value) {
        if (value == null) return false;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String replaceExtension(String input, String extension) {
        String file = Path.of(input).getFileName().toString();
        int dot = file.lastIndexOf('.');
        return (dot > 0 ? file.substring(0, dot) : file) + "." + extension;
    }

    public record Settings(Path binary, String languages, String version) {
        public Settings {
            if (binary == null) throw new IllegalArgumentException("缺少 Tesseract 路径");
            languages = normalizeLanguages(languages);
            version = version == null || version.isBlank() ? "unknown" : version;
        }
    }

    private record ImageDimensions(int width, int height) { }

    private static final class OcrLine {
        private final StringBuilder text = new StringBuilder();
        private int left = Integer.MAX_VALUE;
        private int top = Integer.MAX_VALUE;
        private int right;
        private int bottom;

        void add(String word, int x, int y, int width, int height) {
            if (!text.isEmpty()) text.append(' ');
            text.append(word);
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x + width);
            bottom = Math.max(bottom, y + height);
        }

        String text() { return text.toString(); }
        int width() { return Math.max(1, right - left); }
        int height() { return Math.max(1, bottom - top); }
    }
}
