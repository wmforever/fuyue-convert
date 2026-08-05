package cn.tensafe.ofd2word.task;

import cn.tensafe.ofd2word.docx.DocxRenderer;
import cn.tensafe.ofd2word.model.ConversionWarning;
import cn.tensafe.ofd2word.model.DocumentModel;
import cn.tensafe.ofd2word.model.PageModel;
import cn.tensafe.ofd2word.parser.*;
import cn.tensafe.ofd2word.table.PageLayoutAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ConversionTaskService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConversionTaskService.class);
    private final TaskServiceConfig config;
    private final SafeOfdExtractor extractor;
    private final OfdParser parser;
    private final PageLayoutAnalyzer analyzer;
    private final DocxRenderer renderer;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ConcurrentMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService cleaner;

    public ConversionTaskService(TaskServiceConfig config, SafeOfdExtractor extractor, OfdParser parser,
                                 PageLayoutAnalyzer analyzer, DocxRenderer renderer) throws IOException {
        this.config = config;
        this.extractor = extractor;
        this.parser = parser;
        this.analyzer = analyzer;
        this.renderer = renderer;
        Files.createDirectories(config.dataRoot().resolve("tasks"));
        this.executor = new ThreadPoolExecutor(config.concurrency(), config.concurrency(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()), namedFactory("ofd-converter-"), new ThreadPoolExecutor.AbortPolicy());
        this.cleaner = Executors.newSingleThreadScheduledExecutor(namedFactory("ofd-cleaner-"));
        recoverManifests();
        cleaner.scheduleWithFixedDelay(this::cleanupExpiredSafely, 1, 1, TimeUnit.HOURS);
    }

    public TaskSnapshot createTask(List<UploadPayload> uploads) throws IOException {
        if (uploads == null || uploads.isEmpty()) throw new IllegalArgumentException("至少上传一个 OFD 文件");
        String taskId = UUID.randomUUID().toString();
        Path taskDir = taskDir(taskId);
        Path inputDir = Files.createDirectories(taskDir.resolve("input"));
        Files.createDirectories(taskDir.resolve("work"));
        Files.createDirectories(taskDir.resolve("output"));
        List<InputFile> inputs = new ArrayList<>();
        try {
            for (int i = 0; i < uploads.size(); i++) {
                UploadPayload upload = uploads.get(i);
                validateUploadMetadata(upload);
                Path stored = inputDir.resolve("input-%04d.ofd".formatted(i + 1));
                try (var in = new BufferedInputStream(upload.source().open());
                     var out = new BufferedOutputStream(Files.newOutputStream(stored, StandardOpenOption.CREATE_NEW))) {
                    long copied = in.transferTo(out);
                    if (copied != upload.size() || copied > config.parseLimits().maxArchiveBytes()) {
                        throw new IOException("上传文件大小与声明不一致或超过限制");
                    }
                }
                inputs.add(new InputFile(safeDisplayName(upload.originalName(), i), stored));
            }
        } catch (Exception e) {
            deleteTree(taskDir);
            if (e instanceof IOException io) throw io;
            throw e;
        }

        Instant now = Instant.now();
        TaskRecord record = new TaskRecord(taskId, taskDir, inputs,
                new TaskSnapshot(taskId, TaskStatus.WAITING, TaskStage.QUEUED, 0, null, null,
                        List.of(), List.of(), false, null, now, now, now.plus(config.resultTtl())));
        tasks.put(taskId, record);
        persist(record);
        try {
            executor.execute(() -> convert(record));
        } catch (RejectedExecutionException e) {
            tasks.remove(taskId);
            deleteTree(taskDir);
            throw new TaskQueueFullException();
        }
        return record.snapshot();
    }

    public TaskSnapshot get(String taskId) { return record(taskId).snapshot(); }

    public DownloadArtifact download(String taskId) {
        TaskRecord record = record(taskId);
        TaskSnapshot snapshot = record.snapshot();
        if (!snapshot.downloadReady() || record.downloadPath == null || !Files.isRegularFile(record.downloadPath)) {
            throw new IllegalStateException("任务结果尚不可下载");
        }
        String type = snapshot.downloadName().endsWith(".zip") ? "application/zip"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return new DownloadArtifact(record.downloadPath, snapshot.downloadName(), type);
    }

    public void delete(String taskId) {
        TaskRecord record = tasks.remove(taskId);
        if (record == null) throw new TaskNotFoundException(taskId);
        deleteTree(record.taskDir);
    }

    private void convert(TaskRecord record) {
        Instant deadline = Instant.now().plus(config.timeout());
        List<TaskFileResult> results = new ArrayList<>();
        List<ConversionWarning> warnings = new ArrayList<>();
        List<Path> outputs = new ArrayList<>();
        try {
            update(record, TaskStatus.CONVERTING, TaskStage.VALIDATING, 2, null, null, warnings, results, false, null);
            for (int i = 0; i < record.inputs.size(); i++) {
                checkDeadline(deadline);
                InputFile input = record.inputs.get(i);
                Path work = record.taskDir.resolve("work/file-%04d".formatted(i + 1));
                Path output = record.taskDir.resolve("output/result-%04d.docx".formatted(i + 1));
                Integer parsedPageCount = null;
                try {
                    SafeOfdPackage safe = extractor.extract(input.path, work, config.parseLimits());
                    update(record, TaskStatus.CONVERTING, TaskStage.PARSING, progress(i, record.inputs.size(), 15), null, null, warnings, results, false, null);
                    checkDeadline(deadline);
                    DocumentModel parsed = parser.parse(safe, input.displayName, config.parseLimits());
                    parsedPageCount = parsed.pages().size();
                    warnings.addAll(parsed.warnings());
                    update(record, TaskStatus.CONVERTING, TaskStage.RECOGNIZING, progress(i, record.inputs.size(), 45), null, null, warnings, results, false, null);
                    checkDeadline(deadline);
                    List<PageModel> pages = parsed.pages().stream().map(analyzer::analyze).toList();
                    pages.forEach(p -> warnings.addAll(p.warnings()));
                    DocumentModel analyzed = new DocumentModel(parsed.sourceName(), parsed.parserName(), parsed.sourcePageCount(), pages, parsed.warnings());
                    update(record, TaskStatus.CONVERTING, TaskStage.RENDERING, progress(i, record.inputs.size(), 70), null, null, warnings, results, false, null);
                    checkDeadline(deadline);
                    renderer.render(analyzed, output);
                    outputs.add(output);
                    String outputName = outputFileName(input.displayName);
                    results.add(new TaskFileResult(input.displayName, true, outputName, parsedPageCount, null, null));
                } catch (Exception e) {
                    String code = e instanceof OfdParseException parse ? parse.code() : "CONVERSION_FAILED";
                    results.add(new TaskFileResult(input.displayName, false, null, parsedPageCount, code, safeError(e)));
                    log.warn("taskId={} fileIndex={} conversion failed code={}", record.id, i, code);
                } finally {
                    deleteTree(work);
                    try { Files.deleteIfExists(input.path); } catch (IOException ignored) { }
                }
            }
            if (outputs.isEmpty()) {
                TaskFileResult first = results.get(0);
                update(record, TaskStatus.FAILED, TaskStage.FAILED, 100, first.errorCode(),
                        "所有文件转换失败", warnings, results, false, null);
                return;
            }
            update(record, TaskStatus.CONVERTING, TaskStage.PACKAGING, 95, null, null, warnings, results, false, null);
            if (record.inputs.size() == 1 && outputs.size() == 1) {
                record.downloadPath = outputs.get(0);
                update(record, TaskStatus.SUCCESS, TaskStage.COMPLETED, 100, null, null, warnings, results,
                        true, results.stream().filter(TaskFileResult::success).findFirst().orElseThrow().outputName());
            } else {
                Path zip = record.taskDir.resolve("output/converted-docx.zip");
                packageZip(zip, outputs, results);
                record.downloadPath = zip;
                update(record, TaskStatus.SUCCESS, TaskStage.COMPLETED, 100, null, null, warnings, results,
                        true, "converted-docx.zip");
            }
        } catch (Exception e) {
            update(record, TaskStatus.FAILED, TaskStage.FAILED, 100, "TASK_FAILED", safeError(e),
                    warnings, results, false, null);
            log.error("taskId={} failed at task level", record.id, e);
        }
    }

    private void packageZip(Path zip, List<Path> outputs, List<TaskFileResult> results) throws IOException {
        Set<String> used = new HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
            int index = 0;
            for (TaskFileResult result : results) {
                if (!result.success()) continue;
                String name = uniqueName(result.outputName(), used);
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

    private synchronized void update(TaskRecord record, TaskStatus status, TaskStage stage, int progress,
                                     String errorCode, String errorMessage, List<ConversionWarning> warnings,
                                     List<TaskFileResult> files, boolean ready, String downloadName) {
        TaskSnapshot old = record.snapshot;
        record.snapshot = new TaskSnapshot(record.id, status, stage, Math.max(0, Math.min(100, progress)),
                errorCode, errorMessage, List.copyOf(warnings), List.copyOf(files), ready, downloadName,
                old.createdAt(), Instant.now(), old.expiresAt());
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
            log.error("taskId={} manifest persistence failed", record.id, e);
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
                    if (snapshot.status() == TaskStatus.WAITING || snapshot.status() == TaskStatus.CONVERTING) {
                        snapshot = new TaskSnapshot(snapshot.taskId(), TaskStatus.FAILED, TaskStage.FAILED, 100,
                                "SERVICE_RESTARTED", "服务重启，原转换任务已终止", snapshot.warnings(), snapshot.files(),
                                false, null, snapshot.createdAt(), Instant.now(), snapshot.expiresAt());
                        json.writeValue(manifest.toFile(), snapshot);
                    }
                    TaskRecord record = new TaskRecord(snapshot.taskId(), directory, List.of(), snapshot);
                    if (snapshot.downloadReady()) {
                        Path outputDir = directory.resolve("output");
                        String recoveredDownloadName = snapshot.downloadName();
                        try (var files = Files.list(outputDir)) {
                            record.downloadPath = files.filter(Files::isRegularFile)
                                    .filter(path -> recoveredDownloadName != null &&
                                            (recoveredDownloadName.endsWith(".zip") ? path.toString().endsWith(".zip") : path.toString().endsWith(".docx")))
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

    private void cleanupExpiredSafely() {
        try {
            Instant now = Instant.now();
            for (TaskRecord task : List.copyOf(tasks.values())) {
                if (task.snapshot().expiresAt().isBefore(now) && tasks.remove(task.id, task)) deleteTree(task.taskDir);
            }
        } catch (Exception e) { log.error("Task cleanup failed", e); }
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
    private void validateUploadMetadata(UploadPayload upload) {
        if (upload == null || upload.source() == null || upload.size() <= 0 || upload.size() > config.parseLimits().maxArchiveBytes()) {
            throw new IllegalArgumentException("上传文件为空或超过限制");
        }
        String name = upload.originalName() == null ? "" : upload.originalName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".ofd")) throw new IllegalArgumentException("只允许上传 .ofd 文件");
        String mime = upload.contentType() == null ? "" : upload.contentType().toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!mime.isEmpty() && !Set.of("application/ofd", "application/x-ofd", "application/octet-stream", "application/zip").contains(mime)) {
            throw new IllegalArgumentException("上传文件 MIME 类型不是允许的 OFD/二进制类型");
        }
    }
    private String safeDisplayName(String original, int index) {
        String file = original == null ? "document-%d.ofd".formatted(index + 1) : Paths.get(original).getFileName().toString();
        file = file.replaceAll("[\\r\\n\\t\\x00-\\x1f]", "_");
        return file.length() > 180 ? "document-%d.ofd".formatted(index + 1) : file;
    }
    private String outputFileName(String input) {
        String base = input.replaceFirst("(?i)\\.ofd$", "");
        return base + ".docx";
    }
    private String uniqueName(String proposed, Set<String> used) {
        if (used.add(proposed)) return proposed;
        int dot = proposed.lastIndexOf('.');
        String base = dot < 0 ? proposed : proposed.substring(0, dot);
        String ext = dot < 0 ? "" : proposed.substring(dot);
        for (int i = 2; ; i++) { String candidate = base + "-" + i + ext; if (used.add(candidate)) return candidate; }
    }
    private int progress(int index, int total, int withinFile) { return Math.min(94, (index * 90 + withinFile) / Math.max(1, total)); }
    private void checkDeadline(Instant deadline) throws TimeoutException { if (Instant.now().isAfter(deadline)) throw new TimeoutException("转换超时"); }
    private String safeError(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private ThreadFactory namedFactory(String prefix) {
        return new ThreadFactory() { private int count; public synchronized Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + (++count)); t.setDaemon(true); return t; } };
    }
    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) { log.warn("Could not clean task path {}", root.getFileName()); }
    }

    @Override public void close() { cleaner.shutdownNow(); executor.shutdownNow(); }

    private record InputFile(String displayName, Path path) {}
    private static final class TaskRecord {
        private final String id;
        private final Path taskDir;
        private final List<InputFile> inputs;
        private volatile TaskSnapshot snapshot;
        private volatile Path downloadPath;
        private TaskRecord(String id, Path taskDir, List<InputFile> inputs, TaskSnapshot snapshot) {
            this.id = id; this.taskDir = taskDir; this.inputs = List.copyOf(inputs); this.snapshot = snapshot;
        }
        private TaskSnapshot snapshot() { return snapshot; }
    }
}
