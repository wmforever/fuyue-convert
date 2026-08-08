package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.parser.ParseLimits;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class LibreOfficeConverter implements FileConverter {
    private final ConversionRoute route;
    private final Path binary;
    private final Duration timeout;
    private final String convertTo;

    public LibreOfficeConverter(DocumentFormat sourceFormat, DocumentFormat targetFormat,
                                Path binary, Duration timeout, String description) {
        this.route = route(sourceFormat, targetFormat, description);
        this.binary = binary.toAbsolutePath().normalize();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(2) : timeout;
        this.convertTo = targetFormat.extension();
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        Path outDir = Files.createDirectories(workDir.resolve("office-output"));
        Path profileDir = Files.createDirectories(workDir.resolve("office-profile"));
        progress.update(TaskStage.RENDERING, 25);
        List<String> command = List.of(binary.toString(), "--headless", "--nologo", "--nodefault",
                "--nofirststartwizard", "--nolockcheck",
                "-env:UserInstallation=" + profileDir.toUri(),
                "--convert-to", convertTo, "--outdir", outDir.toString(), input.path().toString());
        ConversionGuards.runProcess(command, workDir.resolve("libreoffice.log"), timeout, "LibreOffice 转换");
        progress.update(TaskStage.PACKAGING, 90);
        Path produced = findProducedFile(outDir, route.targetFormat());
        Files.move(produced, outputPath, StandardCopyOption.REPLACE_EXISTING);
        ConversionGuards.requireNonEmptyOutputFile(outputPath, limits, "LibreOffice");
        return new ConversionOutput(outputPath, outputFileName(input.displayName()), null, List.of());
    }

    private Path findProducedFile(Path outDir, DocumentFormat targetFormat) throws IOException {
        try (var files = Files.list(outDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> targetFormat.acceptsFileName(path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("LibreOffice 未生成 ." + targetFormat.extension() + " 文件"));
        }
    }

    private String outputFileName(String input) {
        String file = Paths.get(input).getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        return base + "." + route.targetFormat().extension();
    }

    private ConversionRoute route(DocumentFormat sourceFormat, DocumentFormat targetFormat, String description) {
        boolean domestic = sourceFormat == DocumentFormat.WPS || sourceFormat == DocumentFormat.ET ||
                sourceFormat == DocumentFormat.DPS || sourceFormat == DocumentFormat.UOF ||
                targetFormat == DocumentFormat.WPS || targetFormat == DocumentFormat.ET ||
                targetFormat == DocumentFormat.DPS || targetFormat == DocumentFormat.UOF;
        return ConversionRoute.of(sourceFormat, targetFormat, description,
                domestic ? QualityLevel.EXPERIMENTAL : QualityLevel.BETA,
                domestic ? ConversionStrategy.COMPATIBILITY : ConversionStrategy.FIDELITY,
                List.of("libreoffice"),
                domestic ? List.of("依赖 LibreOffice 对国产格式的导入兼容性") : List.of("本机字体会影响分页和版式"));
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

    private static boolean probe(Path binary) {
        try {
            Process process = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
