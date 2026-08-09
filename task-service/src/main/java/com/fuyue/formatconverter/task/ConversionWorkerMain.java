package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.parser.OfdParseException;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

public final class ConversionWorkerMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConversionWorkerMain() { }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: ConversionWorkerMain <request.json> <response.json> <progress.json>");
            System.exit(64);
        }
        Path requestPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path responsePath = Path.of(args[1]).toAbsolutePath().normalize();
        Path progressPath = Path.of(args[2]).toAbsolutePath().normalize();
        int exit = run(requestPath, responsePath, progressPath);
        if (exit != 0) System.exit(exit);
    }

    static int run(Path requestPath, Path responsePath, Path progressPath) {
        try {
            WorkerRequest request = JSON.readValue(requestPath.toFile(), WorkerRequest.class);
            DocumentFormat source = DocumentFormat.from(request.sourceFormat())
                    .orElseThrow(() -> new IllegalArgumentException("未知源格式：" + request.sourceFormat()));
            DocumentFormat target = DocumentFormat.from(request.targetFormat())
                    .orElseThrow(() -> new IllegalArgumentException("未知目标格式：" + request.targetFormat()));
            Path office = request.officeBinary() == null || request.officeBinary().isBlank()
                    ? null : Path.of(request.officeBinary());
            List<FileConverter> converters = DefaultConverterRegistry.create(office,
                    Duration.ofMillis(Math.max(1, request.officeTimeoutMillis())));
            FileConverter converter = converters.stream()
                    .filter(item -> item.route().sourceFormat() == source && item.route().targetFormat() == target)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("工作进程未注册转换路线：" + source.id() + " -> " + target.id()));
            Path input = Path.of(request.inputPath()).toAbsolutePath().normalize();
            Path work = Path.of(request.workPath()).toAbsolutePath().normalize();
            Path output = Path.of(request.outputPath()).toAbsolutePath().normalize();
            Files.createDirectories(work);
            Files.createDirectories(output.getParent());
            ConversionOutput converted = converter.convert(
                    new ConversionInput(request.displayName(), request.contentType(), request.size(), input),
                    work, output, request.limits(),
                    (stage, progress) -> writeProgress(progressPath, new WorkerProgress(stage, progress)));
            requireSafeOutputPath(converted.path(), output);
            writeAtomic(responsePath, WorkerResponse.success(converted));
            return 0;
        } catch (Throwable error) {
            String code = error instanceof OfdParseException parsed ? parsed.code()
                    : error instanceof ConversionFailureException failure ? failure.code()
                    : "CONVERSION_FAILED";
            try {
                writeAtomic(responsePath, WorkerResponse.failure(code, safeMessage(error)));
            } catch (Exception writeError) {
                System.err.println("Could not write worker failure: " + safeMessage(writeError));
            }
            return 2;
        }
    }

    private static void requireSafeOutputPath(Path convertedPath, Path requestedPath) throws Exception {
        Path actual = convertedPath.toAbsolutePath().normalize();
        Path outputDirectory = requestedPath.toAbsolutePath().normalize().getParent();
        if (outputDirectory == null || !outputDirectory.equals(actual.getParent())
                || !Files.isRegularFile(actual, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("工作进程返回了输出目录之外或无效的文件");
        }
    }

    private static void writeProgress(Path path, WorkerProgress progress) {
        try {
            writeAtomic(path, progress);
        } catch (Exception ignored) {
            // Progress is best effort; the final response remains authoritative.
        }
    }

    private static void writeAtomic(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
