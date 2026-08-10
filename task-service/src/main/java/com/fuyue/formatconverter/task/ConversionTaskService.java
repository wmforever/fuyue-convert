package com.fuyue.formatconverter.task;

import com.fuyue.formatconverter.docx.DocxRenderer;
import com.fuyue.formatconverter.model.ConversionWarning;
import com.fuyue.formatconverter.parser.*;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ConversionTaskService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConversionTaskService.class);
    private final TaskServiceConfig config;
    private final List<FileConverter> converters;
    private final List<ConversionRoute> plannedRoutes;
    private final Map<String, FileConverter> converterByRoute;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ConcurrentMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService cleaner;

    public ConversionTaskService(TaskServiceConfig config, SafeOfdExtractor extractor, OfdParser parser,
                                 PageLayoutAnalyzer analyzer, DocxRenderer renderer) throws IOException {
        this(config, List.of(
                new OfdToDocxConverter(extractor, parser, analyzer, renderer),
                new OfdToTextConverter(extractor, parser, analyzer),
                new OfdToPdfConverter(extractor, parser, analyzer),
                new OfdToPngConverter(extractor, parser),
                new OfdToJpgConverter(extractor, parser),
                new OfdToXlsxConverter(extractor, parser, analyzer),
                new CsvToXlsxConverter(),
                new XlsxToCsvConverter(),
                new XlsxToPdfConverter(),
                new TextToDocxConverter(),
                new DocxToTextConverter(),
                new TextToPdfConverter(),
                new PdfToTextConverter(),
                new PdfToDocxConverter(new PdfLayoutParser(), analyzer, renderer),
                new PdfToOfdConverter(),
                new DocxToPdfConverter(),
                new PdfToPngConverter(),
                new PdfToJpgConverter(),
                new ImageToPdfConverter(DocumentFormat.PNG),
                new ImageToPdfConverter(DocumentFormat.JPG)
        ), defaultPlannedRoutes());
    }

    public ConversionTaskService(TaskServiceConfig config, List<FileConverter> converters) throws IOException {
        this(config, converters, defaultPlannedRoutes());
    }

    public ConversionTaskService(TaskServiceConfig config, List<FileConverter> converters,
                                 List<ConversionRoute> plannedRoutes) throws IOException {
        this.config = config;
        if (converters == null || converters.isEmpty()) throw new IllegalArgumentException("至少需要注册一个转换器");
        Map<String, FileConverter> indexed = new LinkedHashMap<>();
        for (FileConverter converter : converters) {
            if (converter == null || converter.route() == null) throw new IllegalArgumentException("转换器定义不完整");
            FileConverter previous = indexed.put(routeKey(converter.route().sourceFormat(), converter.route().targetFormat()), converter);
            if (previous != null) throw new IllegalArgumentException("重复的转换路线：" + converter.route().id());
        }
        this.converters = List.copyOf(converters);
        this.converterByRoute = Map.copyOf(indexed);
        this.plannedRoutes = plannedRoutes == null ? List.of() : plannedRoutes.stream()
                .filter(route -> !indexed.containsKey(routeKey(route.sourceFormat(), route.targetFormat())))
                .toList();
        Files.createDirectories(config.dataRoot().resolve("tasks"));
        this.executor = new ThreadPoolExecutor(config.concurrency(), config.concurrency(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()), namedFactory("format-converter-"), new ThreadPoolExecutor.AbortPolicy());
        this.cleaner = Executors.newSingleThreadScheduledExecutor(namedFactory("format-cleaner-"));
        recoverManifests();
        cleaner.scheduleWithFixedDelay(this::cleanupExpiredSafely, 1, 1, TimeUnit.HOURS);
    }

    public TaskSnapshot createTask(List<UploadPayload> uploads) throws IOException {
        return createTask(uploads, DocumentFormat.DOCX);
    }

    public TaskSnapshot createTask(List<UploadPayload> uploads, DocumentFormat targetFormat) throws IOException {
        if (uploads == null || uploads.isEmpty()) throw new IllegalArgumentException("至少上传一个文件");
        if (uploads.size() > config.maxFilesPerTask()) {
            throw new IllegalArgumentException("单任务文件数量超过限制：" + uploads.size() + " > " + config.maxFilesPerTask());
        }
        if (targetFormat == null) throw new IllegalArgumentException("请选择目标格式");
        long totalUploadBytes = totalUploadBytes(uploads);
        ensureStorageCapacity(totalUploadBytes);
        TaskPlan plan = plan(uploads, targetFormat);
        String taskId = UUID.randomUUID().toString();
        Path taskDir = taskDir(taskId);
        Path inputDir = Files.createDirectories(taskDir.resolve("input"));
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("output"));
        List<InputFile> inputs = new ArrayList<>();
        try {
            for (int i = 0; i < uploads.size(); i++) {
                UploadPayload upload = uploads.get(i);
                validateUploadMetadata(upload, plan.route().sourceFormat());
                Path stored = inputDir.resolve("input-%04d.%s".formatted(i + 1, plan.route().sourceFormat().extension()));
                try (var in = new BufferedInputStream(upload.source().open());
                     var out = new BufferedOutputStream(Files.newOutputStream(stored, StandardOpenOption.CREATE_NEW))) {
                    ConversionGuards.copyLimited(in, out, upload.size(), config.parseLimits().maxArchiveBytes());
                }
                validateStoredFile(stored, plan.route().sourceFormat());
                inputs.add(new InputFile(safeDisplayName(upload.originalName(), i, plan.route().sourceFormat()),
                        upload.contentType(), upload.size(), stored));
            }
        } catch (Exception e) {
            deleteTree(taskDir);
            if (e instanceof IOException io) throw io;
            throw e;
        }

        Instant now = Instant.now();
        TaskRecord record = new TaskRecord(taskId, taskDir, inputs,
                new TaskSnapshot(taskId, TaskStatus.WAITING, TaskStage.QUEUED, 0, null, null,
                        List.of(), List.of(), false, null, plan.route().sourceFormat(), plan.route().targetFormat(),
                        now, now, now.plus(config.resultTtl())), plan.converter(), plan.route());
        tasks.put(taskId, record);
        persist(record);
        try {
            record.execution = executor.submit(() -> convert(record));
        } catch (RejectedExecutionException e) {
            tasks.remove(taskId);
            deleteTree(taskDir);
            throw new TaskQueueFullException();
        }
        return record.snapshot();
    }

    public List<ConversionRoute> supportedConversions() {
        List<ConversionRoute> routes = new ArrayList<>();
        converters.stream().map(FileConverter::route).forEach(routes::add);
        routes.addAll(plannedRoutes);
        return List.copyOf(routes);
    }

    public TaskSnapshot get(String taskId) { return record(taskId).snapshot(); }

    public TaskSnapshot cancel(String taskId) {
        TaskRecord record = record(taskId);
        TaskSnapshot current = record.snapshot();
        if (isTerminal(current.status())) return current;
        record.cancellationRequested.set(true);
        update(record, TaskStatus.CANCELLED, TaskStage.CANCELLED, current.progress(), "TASK_CANCELLED",
                "任务已取消", current.warnings(), current.files(), false, null);
        Future<?> execution = record.execution;
        if (execution != null) execution.cancel(true);
        executor.purge();
        return record.snapshot();
    }

    public TaskSnapshot retry(String taskId) throws IOException {
        TaskRecord source = record(taskId);
        TaskStatus status = source.snapshot().status();
        if (status != TaskStatus.FAILED && status != TaskStatus.CANCELLED) {
            throw new IllegalStateException("只有失败或已取消的任务可以重试");
        }
        if (source.inputs.isEmpty() || source.inputs.stream().anyMatch(input -> !Files.isRegularFile(input.path))) {
            throw new IllegalStateException("原始上传文件已过期，无法重试");
        }
        List<UploadPayload> uploads = source.inputs.stream().map(input ->
                new UploadPayload(input.displayName, input.contentType, input.size,
                        () -> Files.newInputStream(input.path))).toList();
        return createTask(uploads, source.route.targetFormat());
    }

    public DownloadArtifact download(String taskId) {
        TaskRecord record = record(taskId);
        TaskSnapshot snapshot = record.snapshot();
        if (!snapshot.downloadReady() || record.downloadPath == null || !Files.isRegularFile(record.downloadPath)) {
            throw new IllegalStateException("任务结果尚不可下载");
        }
        String type = snapshot.downloadName().endsWith(".zip") ? "application/zip"
                : snapshot.targetFormat().contentType();
        return new DownloadArtifact(record.downloadPath, snapshot.downloadName(), type);
    }

    public void delete(String taskId) {
        TaskRecord record = tasks.remove(taskId);
        if (record == null) throw new TaskNotFoundException(taskId);
        record.deleteRequested.set(true);
        record.cancellationRequested.set(true);
        Future<?> execution = record.execution;
        if (execution != null && !execution.isDone()) {
            execution.cancel(true);
            executor.purge();
        }
        if (!record.executionStarted.get()) {
            deleteTree(record.taskDir);
        }
    }

    private void convert(TaskRecord record) {
        record.executionStarted.set(true);
        Instant deadline = Instant.now().plus(config.timeout());
        List<TaskFileResult> results = new ArrayList<>();
        List<ConversionWarning> warnings = new ArrayList<>();
        List<Path> outputs = new ArrayList<>();
        long taskOutputBytes = 0;
        try {
            checkCancellation(record);
            update(record, TaskStatus.CONVERTING, TaskStage.VALIDATING, 2, null, null, warnings, results, false, null);
            for (int i = 0; i < record.inputs.size(); i++) {
                int fileIndex = i;
                checkCancellation(record);
                checkDeadline(deadline);
                InputFile input = record.inputs.get(i);
                Path work = record.taskDir.resolve("work/file-%04d".formatted(i + 1));
                Path output = record.taskDir.resolve("output/result-%04d.%s".formatted(i + 1, record.route.targetFormat().extension()));
                Path produced = null;
                Integer parsedPageCount = null;
                AtomicBoolean fileActive = new AtomicBoolean(true);
                try {
                    ConversionInput conversionInput = new ConversionInput(input.displayName, input.contentType, input.size, input.path);
                    ensureStorageCapacity(0);
                    ConversionOutput converted = convertWithTimeout(record, conversionInput, work, output, deadline, (stage, withinFile) -> {
                        if (fileActive.get()) {
                            update(record, TaskStatus.CONVERTING, stage, progress(fileIndex, record.inputs.size(), withinFile), null, null, warnings, results, false, null);
                        }
                    });
                    checkCancellation(record);
                    parsedPageCount = converted.pageCount();
                    ConversionGuards.requireOutputFile(converted.path(), config.parseLimits(), "转换");
                    produced = converted.path();
                    taskOutputBytes = addTaskOutputBytes(taskOutputBytes, Files.size(produced));
                    warnings.addAll(converted.warnings());
                    outputs.add(produced);
                    String outputName = safeOutputName(converted.outputName(), input.displayName, record.route.targetFormat());
                    results.add(new TaskFileResult(input.displayName, true, outputName, parsedPageCount, null, null,
                            record.route.sourceFormat(), record.route.targetFormat()));
                } catch (CancellationException e) {
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("任务已取消");
                } catch (Exception e) {
                    String code = failureCode(e);
                    results.add(new TaskFileResult(input.displayName, false, null, parsedPageCount, code, safeError(e),
                            record.route.sourceFormat(), record.route.targetFormat()));
                    log.warn("taskId={} fileIndex={} conversion failed code={}", record.id, i, code);
                    if (produced != null) try { Files.deleteIfExists(produced); } catch (IOException ignored) { }
                    try { Files.deleteIfExists(output); } catch (IOException ignored) { }
                } finally {
                    fileActive.set(false);
                    deleteTree(work);
                }
            }
            checkCancellation(record);
            if (outputs.isEmpty()) {
                TaskFileResult first = results.get(0);
                String message = first.errorMessage() == null || first.errorMessage().isBlank()
                        ? "所有文件转换失败"
                        : "所有文件转换失败：" + first.errorMessage();
                update(record, TaskStatus.FAILED, TaskStage.FAILED, 100, first.errorCode(),
                        message, warnings, results, false, null);
                return;
            }
            update(record, TaskStatus.CONVERTING, TaskStage.PACKAGING, 95, null, null, warnings, results, false, null);
            if (record.inputs.size() == 1 && outputs.size() == 1) {
                record.downloadPath = outputs.get(0);
                update(record, TaskStatus.SUCCESS, TaskStage.COMPLETED, 100, null, null, warnings, results,
                        true, results.stream().filter(TaskFileResult::success).findFirst().orElseThrow().outputName());
            } else if (isImageToPdf(record.route)) {
                ensureStorageCapacity(taskOutputBytes);
                Path merged = record.taskDir.resolve("output/merged-images.pdf");
                mergePdfs(outputs, merged);
                requireTaskOutputFile(merged, "图片合并 PDF");
                int mergedPages = ConversionGuards.requirePdfPageCount(merged, config.parseLimits());
                if (mergedPages != outputs.size()) {
                    throw new IOException("图片合并 PDF 页数不一致：" + mergedPages + " != " + outputs.size());
                }
                if (outputs.size() != record.inputs.size()) {
                    warnings.add(ConversionWarning.of(com.fuyue.formatconverter.model.WarningCode.PARTIAL_BATCH_OUTPUT,
                            "部分图片转换失败；合并 PDF 仅包含成功的 " + outputs.size() + " / "
                                    + record.inputs.size() + " 张图片。", null));
                }
                record.downloadPath = merged;
                deleteArtifactsExcept(outputs, merged);
                update(record, TaskStatus.SUCCESS, TaskStage.COMPLETED, 100, null, null, warnings, results,
                        true, "merged-images.pdf");
            } else {
                ensureStorageCapacity(taskOutputBytes);
                Path zip = record.taskDir.resolve("output/converted-to-" + record.route.targetFormat().extension() + ".zip");
                packageZip(zip, outputs, results);
                requireTaskOutputFile(zip, "ZIP 打包");
                record.downloadPath = zip;
                deleteArtifactsExcept(outputs, zip);
                update(record, TaskStatus.SUCCESS, TaskStage.COMPLETED, 100, null, null, warnings, results,
                        true, "converted-to-" + record.route.targetFormat().extension() + ".zip");
            }
        } catch (CancellationException e) {
            update(record, TaskStatus.CANCELLED, TaskStage.CANCELLED, record.snapshot().progress(),
                    "TASK_CANCELLED", "任务已取消", warnings, results, false, null);
        } catch (Exception e) {
            String code = e instanceof ConversionFailureException failure ? failure.code() : "TASK_FAILED";
            update(record, TaskStatus.FAILED, TaskStage.FAILED, 100, code, safeError(e),
                    warnings, results, false, null);
            log.error("taskId={} failed at task level type={} reason={}", record.id,
                    e.getClass().getSimpleName(), safeError(e));
        } catch (Error e) {
            update(record, TaskStatus.FAILED, TaskStage.FAILED, 100, "CONVERTER_CRASHED",
                    "转换组件异常终止：" + e.getClass().getSimpleName(), warnings, results, false, null);
            log.error("taskId={} converter crashed type={}", record.id, e.getClass().getSimpleName());
        } finally {
            record.executionStarted.set(false);
            if (record.deleteRequested.get()) deleteTree(record.taskDir);
            else if (record.snapshot().status() == TaskStatus.SUCCESS) deleteInputs(record);
        }
    }

    private boolean isImageToPdf(ConversionRoute route) {
        return route.targetFormat() == DocumentFormat.PDF
                && (route.sourceFormat() == DocumentFormat.PNG || route.sourceFormat() == DocumentFormat.JPG);
    }

    private void mergePdfs(List<Path> sources, Path destination) throws IOException {
        PDFMergerUtility merger = new PDFMergerUtility();
        for (Path source : sources) merger.addSource(source.toFile());
        merger.setDestinationFileName(destination.toString());
        merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
    }

    private void packageZip(Path zip, List<Path> outputs, List<TaskFileResult> results) throws IOException {
        Set<String> used = new HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            int index = 0;
            for (TaskFileResult result : results) {
                if (!result.success()) continue;
                String name = uniqueName(safeArchiveEntryName(result.outputName()), used);
                out.putNextEntry(new ZipEntry(name));
                Files.copy(outputs.get(index++), out);
                out.closeEntry();
            }
            String report = results.stream().filter(r -> !r.success())
                    .map(r -> r.fileName() + "\t" + r.errorCode() + "\t" + r.errorMessage())
                    .reduce("", (a, b) -> a + b + "\n");
            if (!report.isBlank()) {
                out.putNextEntry(new ZipEntry("conversion-report.txt"));
                out.write(report.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private ConversionOutput convertWithTimeout(TaskRecord record, ConversionInput input, Path work, Path output,
                                                Instant deadline, ConversionProgress progress) throws Exception {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) throw new TimeoutException("转换超时");
        ExecutorService single = Executors.newSingleThreadExecutor(namedFactory("format-file-"));
        Future<ConversionOutput> future = single.submit(() ->
                record.converter.convert(input, work, output, config.parseLimits(), progress));
        try {
            return future.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("转换超时");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) {
                throw new IOException("转换组件异常终止：" + error.getClass().getSimpleName(), error);
            }
            throw new RuntimeException(cause);
        } finally {
            single.shutdownNow();
        }
    }

    private synchronized void update(TaskRecord record, TaskStatus status, TaskStage stage, int progress,
                                     String errorCode, String errorMessage, List<ConversionWarning> warnings,
                                     List<TaskFileResult> files, boolean ready, String downloadName) {
        if (record.deleteRequested.get()) return;
        if (record.cancellationRequested.get() && status != TaskStatus.CANCELLED) return;
        TaskSnapshot old = record.snapshot;
        Instant updatedAt = Instant.now();
        Instant expiresAt = isTerminal(status) ? updatedAt.plus(config.resultTtl()) : old.expiresAt();
        record.snapshot = new TaskSnapshot(record.id, status, stage, Math.max(0, Math.min(100, progress)),
                errorCode, errorMessage, List.copyOf(warnings), List.copyOf(files), ready, downloadName,
                old.sourceFormat(), old.targetFormat(),
                old.createdAt(), updatedAt, expiresAt);
        persist(record);
    }

    private void persist(TaskRecord record) {
        try {
            Path target = record.taskDir.resolve("manifest.json");
            Path temporary = record.taskDir.resolve("manifest.json.tmp");
            json.writeValue(temporary.toFile(), record.snapshot());
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            log.error("taskId={} manifest persistence failed reason={}", record.id, safeError(e));
        }
    }

    private void recoverManifests() throws IOException {
        Path root = config.dataRoot().resolve("tasks");
        try (var directories = Files.list(root)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path manifest = directory.resolve("manifest.json");
                if (!Files.isRegularFile(manifest)) continue;
                try {
                    TaskSnapshot snapshot = json.readValue(manifest.toFile(), TaskSnapshot.class);
                    if (snapshot.expiresAt().isBefore(Instant.now())) {
                        deleteTree(directory);
                        continue;
                    }
                    if (snapshot.status() == TaskStatus.WAITING || snapshot.status() == TaskStatus.CONVERTING) {
                        snapshot = new TaskSnapshot(snapshot.taskId(), TaskStatus.FAILED, TaskStage.FAILED, 100,
                                "SERVICE_RESTARTED", "服务重启，原转换任务已终止", snapshot.warnings(), snapshot.files(),
                                false, null, snapshot.sourceFormat(), snapshot.targetFormat(),
                                snapshot.createdAt(), Instant.now(), Instant.now().plus(config.resultTtl()));
                        json.writeValue(manifest.toFile(), snapshot);
                    }
                    FileConverter converter = converterByRoute.get(routeKey(snapshot.sourceFormat(), snapshot.targetFormat()));
                    ConversionRoute route = converter == null
                            ? ConversionRoute.of(snapshot.sourceFormat(), snapshot.targetFormat(), "已恢复的历史任务")
                            : converter.route();
                    TaskRecord record = new TaskRecord(snapshot.taskId(), directory,
                            recoverInputs(directory, snapshot), snapshot, converter, route);
                    if (snapshot.downloadReady()) {
                        Path outputDir = directory.resolve("output");
                        String recoveredDownloadName = snapshot.downloadName();
                        DocumentFormat recoveredTargetFormat = snapshot.targetFormat();
                        try (var files = Files.list(outputDir)) {
                            record.downloadPath = files.filter(Files::isRegularFile)
                                    .filter(path -> recoveredDownloadName != null &&
                                            (recoveredDownloadName.endsWith(".zip") ? path.toString().endsWith(".zip") :
                                                    path.toString().endsWith("." + recoveredTargetFormat.extension())))
                                    .findFirst().orElse(null);
                        }
                    }
                    tasks.put(snapshot.taskId(), record);
                } catch (Exception e) {
                    log.warn("Could not recover task manifest at {}", directory.getFileName());
                }
            }
        }
    }

    private List<InputFile> recoverInputs(Path directory, TaskSnapshot snapshot) throws IOException {
        Path inputDir = directory.resolve("input");
        if (!Files.isDirectory(inputDir)) return List.of();
        try (var files = Files.list(inputDir)) {
            List<Path> paths = files.filter(Files::isRegularFile).sorted().toList();
            List<InputFile> recovered = new ArrayList<>();
            for (int index = 0; index < paths.size(); index++) {
                Path path = paths.get(index);
                String displayName = index < snapshot.files().size()
                        ? snapshot.files().get(index).fileName()
                        : "document-%d.%s".formatted(index + 1, snapshot.sourceFormat().extension());
                recovered.add(new InputFile(displayName, snapshot.sourceFormat().contentType(), Files.size(path), path));
            }
            return List.copyOf(recovered);
        }
    }

    private void cleanupExpiredSafely() {
        try {
            Instant now = Instant.now();
            for (TaskRecord task : List.copyOf(tasks.values())) {
                if (isTerminal(task.snapshot().status()) && task.snapshot().expiresAt().isBefore(now)
                        && tasks.remove(task.id, task)) {
                    task.deleteRequested.set(true);
                    task.cancellationRequested.set(true);
                    Future<?> execution = task.execution;
                    if (execution != null && !execution.isDone()) execution.cancel(true);
                    if (!task.executionStarted.get()) deleteTree(task.taskDir);
                }
            }
            executor.purge();
        } catch (Exception e) { log.error("Task cleanup failed reason={}", safeError(e)); }
    }

    private TaskRecord record(String taskId) {
        try { UUID.fromString(taskId); } catch (Exception e) { throw new TaskNotFoundException(taskId); }
        TaskRecord value = tasks.get(taskId);
        if (value == null) throw new TaskNotFoundException(taskId);
        return value;
    }
    private Path taskDir(String taskId) {
        Path root = config.dataRoot().resolve("tasks").toAbsolutePath().normalize();
        Path target = root.resolve(taskId).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid task path");
        return target;
    }
    private TaskPlan plan(List<UploadPayload> uploads, DocumentFormat targetFormat) {
        TaskPlan plan = null;
        for (UploadPayload upload : uploads) {
            String name = upload == null ? null : upload.originalName();
            DocumentFormat source = DocumentFormat.fromFileName(name)
                    .orElseThrow(() -> new IllegalArgumentException("暂不支持的源文件格式：" + safeName(name)));
            FileConverter converter = converter(source, targetFormat);
            if (plan == null) {
                plan = new TaskPlan(converter, converter.route());
            } else if (!plan.route().id().equals(converter.route().id())) {
                throw new IllegalArgumentException("同一任务目前只支持相同的源格式和目标格式");
            }
        }
        return Objects.requireNonNull(plan);
    }

    private FileConverter converter(DocumentFormat sourceFormat, DocumentFormat targetFormat) {
        FileConverter converter = converterByRoute.get(routeKey(sourceFormat, targetFormat));
        if (converter == null) {
            throw new IllegalArgumentException("暂不支持 " + sourceFormat.label() + " 到 " + targetFormat.label() + " 的转换");
        }
        return converter;
    }

    private void validateUploadMetadata(UploadPayload upload, DocumentFormat sourceFormat) {
        if (upload == null || upload.source() == null || upload.size() <= 0 || upload.size() > config.parseLimits().maxArchiveBytes()) {
            throw new IllegalArgumentException("上传文件为空或超过限制");
        }
        if (!sourceFormat.acceptsFileName(upload.originalName())) {
            throw new IllegalArgumentException("只允许上传 ." + sourceFormat.extension() + " 文件");
        }
        if (!sourceFormat.acceptsMimeType(upload.contentType())) {
            throw new IllegalArgumentException("上传文件 MIME 类型不是允许的 " + sourceFormat.label() + " 类型");
        }
    }
    private long totalUploadBytes(List<UploadPayload> uploads) {
        long total = 0;
        for (UploadPayload upload : uploads) {
            if (upload == null || upload.size() <= 0) throw new IllegalArgumentException("上传文件为空或超过限制");
            try { total = Math.addExact(total, upload.size()); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("单任务上传总量超过限制"); }
            if (total > config.maxTaskUploadBytes()) throw new IllegalArgumentException("单任务上传总量超过限制");
        }
        return total;
    }
    private void ensureStorageCapacity(long incomingBytes) {
        try {
            long usable = Files.getFileStore(config.dataRoot()).getUsableSpace();
            if (usable < incomingBytes || usable - incomingBytes < config.minFreeDiskBytes()) {
                throw new InsufficientStorageException("存储空间低于安全水位，请清理过期任务后重试");
            }
        } catch (InsufficientStorageException e) {
            throw e;
        } catch (IOException e) {
            throw new InsufficientStorageException("无法确认任务存储空间，请检查数据盘状态");
        }
    }

    private long addTaskOutputBytes(long current, long additional) throws ConversionFailureException {
        long total;
        try { total = Math.addExact(current, additional); }
        catch (ArithmeticException e) {
            throw new ConversionFailureException("TASK_OUTPUT_LIMIT_EXCEEDED", "单任务输出总量超过限制");
        }
        if (total > config.maxTaskOutputBytes()) {
            throw new ConversionFailureException("TASK_OUTPUT_LIMIT_EXCEEDED",
                    "单任务输出总量超过限制：" + total + " > " + config.maxTaskOutputBytes());
        }
        return total;
    }

    private void requireTaskOutputFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) throw new IOException(label + "未生成有效输出文件");
        long size = Files.size(path);
        if (size > config.maxTaskOutputBytes()) {
            throw new ConversionFailureException("TASK_OUTPUT_LIMIT_EXCEEDED",
                    label + "超过单任务输出限制：" + size + " > " + config.maxTaskOutputBytes());
        }
    }

    private void deleteArtifactsExcept(List<Path> artifacts, Path retained) {
        Path keep = retained.toAbsolutePath().normalize();
        for (Path artifact : artifacts) {
            if (artifact == null || artifact.toAbsolutePath().normalize().equals(keep)) continue;
            try { Files.deleteIfExists(artifact); }
            catch (IOException e) { log.warn("Could not remove intermediate output {}", artifact.getFileName()); }
        }
    }
    private void validateStoredFile(Path file, DocumentFormat sourceFormat) throws IOException {
        byte[] header = readHeader(file, 16);
        boolean ok = switch (sourceFormat) {
            case PDF -> startsWith(header, "%PDF".getBytes(StandardCharsets.US_ASCII));
            case PNG -> startsWith(header, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case JPG -> startsWith(header, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case DOCX, XLSX, PPTX, OFD -> isZip(header);
            case UOF -> isZip(header) || looksLikeXml(file);
            case WPS, ET, DPS -> isOle(header) || isZip(header);
            case TXT, CSV, HTML -> looksLikeText(file);
        };
        if (!ok) throw new IllegalArgumentException(sourceFormat.label() + " 文件头校验失败，请确认文件未损坏且格式真实");
    }
    private byte[] readHeader(Path file, int length) throws IOException {
        byte[] data = new byte[length];
        try (var in = Files.newInputStream(file)) {
            int read = in.read(data);
            return read <= 0 ? new byte[0] : Arrays.copyOf(data, read);
        }
    }
    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
    private boolean isZip(byte[] header) { return startsWith(header, new byte[] {0x50, 0x4B, 0x03, 0x04}) || startsWith(header, new byte[] {0x50, 0x4B, 0x05, 0x06}) || startsWith(header, new byte[] {0x50, 0x4B, 0x07, 0x08}); }
    private boolean isOle(byte[] header) { return startsWith(header, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}); }
    private boolean looksLikeText(Path file) throws IOException {
        byte[] data = readHeader(file, 4096);
        for (byte datum : data) if (datum == 0) return false;
        return true;
    }
    private boolean looksLikeXml(Path file) throws IOException {
        byte[] data = readHeader(file, 4096);
        for (byte datum : data) if (datum == 0) return false;
        String sample = new String(data, StandardCharsets.UTF_8).stripLeading();
        return sample.startsWith("<?xml") || sample.startsWith("<");
    }
    private String safeDisplayName(String original, int index, DocumentFormat sourceFormat) {
        String fallback = "document-%d.%s".formatted(index + 1, sourceFormat.extension());
        String file = portableBaseName(original);
        file = file.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]", "_").strip();
        return file.isBlank() || file.equals(".") || file.equals("..") || file.length() > 180 ? fallback : file;
    }
    private String safeName(String name) {
        String file = portableBaseName(name).replaceAll("[\\x00-\\x1f\\x7f]", "_");
        return file.isBlank() ? "未知文件" : file;
    }
    private String portableBaseName(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
    private String safeOutputName(String proposed, String inputName, DocumentFormat targetFormat) {
        String fallbackBase = portableBaseName(inputName);
        int dot = fallbackBase.lastIndexOf('.');
        if (dot > 0) fallbackBase = fallbackBase.substring(0, dot);
        if (fallbackBase.isBlank()) fallbackBase = "result";
        String fallback = fallbackBase + "." + targetFormat.extension();
        String safe = portableBaseName(proposed).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]", "_").strip();
        return safe.isBlank() || safe.equals(".") || safe.equals("..") || safe.length() > 180 ? fallback : safe;
    }
    private String safeArchiveEntryName(String proposed) {
        String safe = portableBaseName(proposed).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]", "_").strip();
        return safe.isBlank() || safe.equals(".") || safe.equals("..") ? "result" : safe;
    }
    private String routeKey(DocumentFormat sourceFormat, DocumentFormat targetFormat) { return sourceFormat.id() + "->" + targetFormat.id(); }
    private static List<ConversionRoute> defaultPlannedRoutes() {
        return List.of(
                ConversionRoute.planned(DocumentFormat.DOCX, DocumentFormat.PDF, "将 Word 文档导出为 PDF，需接入本地 Office/LibreOffice 或 Java 渲染引擎。"),
                ConversionRoute.planned(DocumentFormat.PDF, DocumentFormat.DOCX, "将 PDF 转为可编辑 Word，需先区分文字型 PDF 和扫描型 PDF。"),
                ConversionRoute.planned(DocumentFormat.PPTX, DocumentFormat.PDF, "将演示文稿导出为 PDF。"),
                ConversionRoute.planned(DocumentFormat.PDF, DocumentFormat.PNG, "将 PDF 按页渲染为 PNG，批量页输出 ZIP。"),
                ConversionRoute.planned(DocumentFormat.PNG, DocumentFormat.PDF, "将 PNG 图片合成为 PDF。"),
                ConversionRoute.planned(DocumentFormat.JPG, DocumentFormat.PDF, "将 JPEG 图片合成为 PDF。"),
                ConversionRoute.planned(DocumentFormat.PNG, DocumentFormat.TXT, "使用显式配置的本地 OCR 提取 PNG 文字。"),
                ConversionRoute.planned(DocumentFormat.JPG, DocumentFormat.TXT, "使用显式配置的本地 OCR 提取 JPEG 文字。"),
                ConversionRoute.planned(DocumentFormat.PNG, DocumentFormat.DOCX, "使用显式配置的本地 OCR 生成可编辑 Word。"),
                ConversionRoute.planned(DocumentFormat.JPG, DocumentFormat.DOCX, "使用显式配置的本地 OCR 生成可编辑 Word。"),
                ConversionRoute.planned(DocumentFormat.OFD, DocumentFormat.PDF, "将 OFD 直接渲染为 PDF。"),
                ConversionRoute.planned(DocumentFormat.PDF, DocumentFormat.OFD, "将 PDF 转为 OFD，需接入 OFD 生成器并明确版式保真策略。"),
                ConversionRoute.planned(DocumentFormat.OFD, DocumentFormat.XLSX, "从 OFD 表格识别结果导出 Excel。"),
                ConversionRoute.planned(DocumentFormat.XLSX, DocumentFormat.PDF, "将 Excel 工作簿导出为 PDF。"),
                ConversionRoute.planned(DocumentFormat.WPS, DocumentFormat.DOCX, "将 WPS 文字文档转换为 DOCX。"),
                ConversionRoute.planned(DocumentFormat.DOCX, DocumentFormat.WPS, "将 DOCX 转换为 WPS 文字文档。"),
                ConversionRoute.planned(DocumentFormat.ET, DocumentFormat.XLSX, "将 WPS 表格 ET 转换为 XLSX。"),
                ConversionRoute.planned(DocumentFormat.XLSX, DocumentFormat.ET, "将 XLSX 转换为 WPS 表格 ET。"),
                ConversionRoute.planned(DocumentFormat.DPS, DocumentFormat.PPTX, "将 WPS 演示 DPS 转换为 PPTX。"),
                ConversionRoute.planned(DocumentFormat.PPTX, DocumentFormat.DPS, "将 PPTX 转换为 WPS 演示 DPS。"),
                ConversionRoute.planned(DocumentFormat.UOF, DocumentFormat.DOCX, "将 UOF 国产文档转换为 DOCX。"),
                ConversionRoute.planned(DocumentFormat.DOCX, DocumentFormat.UOF, "将 DOCX 转换为 UOF 国产文档。")
        );
    }
    private String uniqueName(String proposed, Set<String> used) {
        if (used.add(proposed)) return proposed;
        int dot = proposed.lastIndexOf('.');
        String base = dot < 0 ? proposed : proposed.substring(0, dot);
        String ext = dot < 0 ? "" : proposed.substring(dot);
        for (int i = 2; ; i++) { String candidate = base + "-" + i + ext; if (used.add(candidate)) return candidate; }
    }
    private int progress(int index, int total, int withinFile) { return Math.min(94, (index * 90 + withinFile) / Math.max(1, total)); }
    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCESS || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED;
    }
    private void checkCancellation(TaskRecord record) throws InterruptedException {
        if (record.cancellationRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("任务已取消");
        }
    }
    private void checkDeadline(Instant deadline) throws TimeoutException { if (Instant.now().isAfter(deadline)) throw new TimeoutException("转换超时"); }
    private String safeError(Throwable e) { return ErrorMessageSanitizer.from(e); }
    private String failureCode(Exception error) {
        if (error instanceof OfdParseException parsed) return parsed.code();
        if (error instanceof ConversionFailureException failure) return failure.code();
        if (error instanceof TimeoutException) return "CONVERSION_TIMEOUT";
        return "CONVERSION_FAILED";
    }
    private ThreadFactory namedFactory(String prefix) {
        return new ThreadFactory() { private int count; public synchronized Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + (++count)); t.setDaemon(true); return t; } };
    }
    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) { log.warn("Could not clean task path {}", root.getFileName()); }
    }

    private void deleteInputs(TaskRecord record) {
        for (InputFile input : record.inputs) {
            try { Files.deleteIfExists(input.path); }
            catch (IOException e) { log.warn("taskId={} input cleanup failed", record.id); }
        }
    }

    @Override public void close() { cleaner.shutdownNow(); executor.shutdownNow(); }

    private record InputFile(String displayName, String contentType, long size, Path path) {}
    private record TaskPlan(FileConverter converter, ConversionRoute route) {}
    private static final class TaskRecord {
        private final String id;
        private final Path taskDir;
        private final List<InputFile> inputs;
        private final FileConverter converter;
        private final ConversionRoute route;
        private volatile TaskSnapshot snapshot;
        private volatile Path downloadPath;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean deleteRequested = new AtomicBoolean();
        private final AtomicBoolean executionStarted = new AtomicBoolean();
        private volatile Future<?> execution;
        private TaskRecord(String id, Path taskDir, List<InputFile> inputs, TaskSnapshot snapshot,
                           FileConverter converter, ConversionRoute route) {
            this.id = id; this.taskDir = taskDir; this.inputs = List.copyOf(inputs); this.snapshot = snapshot;
            this.converter = converter; this.route = route;
        }
        private TaskSnapshot snapshot() { return snapshot; }
    }
}
