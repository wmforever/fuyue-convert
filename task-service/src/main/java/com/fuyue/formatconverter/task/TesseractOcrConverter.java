package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.model.WarningCode;
import com.fuyue.formatconverter.parser.ParseLimits;

import java.io.IOException;
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
}
