package com.fuyue.formatconverter.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyue.formatconverter.parser.ParseLimits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ForkedFileConverter implements FileConverter {
    private static final int MAX_LOG_BYTES = 16 * 1024;
    private final ConversionRoute route;
    private final List<String> workerCommand;
    private final String officeBinary;
    private final Duration officeTimeout;
    private final ObjectMapper json = new ObjectMapper();

    public ForkedFileConverter(ConversionRoute route, List<String> workerCommand,
                               String officeBinary, Duration officeTimeout) {
        if (route == null) throw new IllegalArgumentException("缺少转换路线");
        if (workerCommand == null || workerCommand.isEmpty()) throw new IllegalArgumentException("缺少 Worker 启动命令");
        this.route = route;
        this.workerCommand = List.copyOf(workerCommand);
        this.officeBinary = officeBinary == null ? "" : officeBinary;
        this.officeTimeout = officeTimeout == null ? Duration.ofMinutes(2) : officeTimeout;
    }

    @Override public ConversionRoute route() { return route; }

    @Override
    public ConversionOutput convert(ConversionInput input, Path workDir, Path outputPath,
                                    ParseLimits limits, ConversionProgress progress) throws Exception {
        Files.createDirectories(workDir);
        Path requestPath = workDir.resolve("worker-request.json");
        Path responsePath = workDir.resolve("worker-response.json");
        Path progressPath = workDir.resolve("worker-progress.json");
        Path logPath = workDir.resolve("worker.log");
        WorkerRequest request = new WorkerRequest(route.sourceFormat().id(), route.targetFormat().id(),
                input.displayName(), input.contentType(), input.size(),
                input.path().toAbsolutePath().normalize().toString(), workDir.resolve("conversion").toString(),
                outputPath.toAbsolutePath().normalize().toString(), limits, officeBinary, officeTimeout.toMillis(),
                input.options());
        json.writeValue(requestPath.toFile(), request);

        List<String> command = new ArrayList<>(workerCommand);
        command.add(requestPath.toString());
        command.add(responsePath.toString());
        command.add(progressPath.toString());
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        ProcessOutputCapture capture = ProcessOutputCapture.start(process, command, MAX_LOG_BYTES);
        Set<ProcessHandle> observedDescendants = new HashSet<>();
        WorkerProgress lastProgress = null;
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                ConversionGuards.observeDescendants(process, observedDescendants);
                lastProgress = relayProgress(progressPath, progress, lastProgress);
            }
            ConversionGuards.observeDescendants(process, observedDescendants);
            relayProgress(progressPath, progress, lastProgress);
        } catch (InterruptedException e) {
            ConversionGuards.terminateProcessTree(process, observedDescendants);
            capture.finish(logPath);
            Thread.currentThread().interrupt();
            throw e;
        }
        ConversionGuards.terminateAlive(observedDescendants);
        String workerLog = capture.finish(logPath);

        WorkerResponse response = readResponse(responsePath);
        if (response == null) {
            throw new ConversionFailureException("WORKER_CRASHED",
                    "转换 Worker 异常退出（exit=" + process.exitValue() + "）：" +
                            (workerLog.isBlank() ? "无 Worker 日志" : workerLog));
        }
        if (!response.success()) {
            throw new ConversionFailureException(response.errorCode(), response.errorMessage());
        }
        if (process.exitValue() != 0) {
            throw new ConversionFailureException("WORKER_CRASHED",
                    "转换 Worker 返回成功结果后异常退出（exit=" + process.exitValue() + "）");
        }
        Path actual = Path.of(response.outputPath()).toAbsolutePath().normalize();
        Path expected = outputPath.toAbsolutePath().normalize();
        if (expected.getParent() == null || !expected.getParent().equals(actual.getParent())
                || !Files.isRegularFile(actual, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new ConversionFailureException("WORKER_OUTPUT_INVALID", "转换 Worker 未生成预期输出文件");
        }
        return new ConversionOutput(actual, response.outputName(), response.pageCount(), response.warnings());
    }

    private WorkerResponse readResponse(Path responsePath) throws IOException {
        if (!Files.isRegularFile(responsePath)) return null;
        try {
            return json.readValue(responsePath.toFile(), WorkerResponse.class);
        } catch (IOException e) {
            throw new ConversionFailureException("WORKER_RESPONSE_INVALID", "转换 Worker 响应无法解析");
        }
    }

    private WorkerProgress relayProgress(Path progressPath, ConversionProgress progress, WorkerProgress previous) {
        if (!Files.isRegularFile(progressPath)) return previous;
        try {
            WorkerProgress update = json.readValue(progressPath.toFile(), WorkerProgress.class);
            if (update.stage() != null && !update.equals(previous)) progress.update(update.stage(), update.progress());
            return update;
        } catch (Exception ignored) {
            // Atomic writes make partial reads unlikely; a later poll can recover.
            return previous;
        }
    }

}
