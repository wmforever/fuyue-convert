package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final ConversionTaskService tasks;

    public TaskController(ConversionTaskService tasks) { this.tasks = tasks; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskSnapshot> create(@RequestPart("files") List<MultipartFile> files,
                                               @RequestParam(defaultValue = "docx") String targetFormat,
                                               @RequestParam(required = false) String compressionMode,
                                               @RequestParam(required = false) String watermarkText,
                                               @RequestParam(required = false) Double watermarkOpacity,
                                               @RequestParam(required = false) Double watermarkAngle,
                                               @RequestParam(required = false) String watermarkPosition,
                                               @RequestParam(required = false) Boolean watermarkTiled,
                                               @RequestParam(required = false) String watermarkPages,
                                               @RequestParam(required = false) String watermarkColor,
                                               @RequestParam(required = false) String splitPages,
                                               @RequestParam(required = false) Integer imageDpi) throws IOException {
        DocumentFormat target = DocumentFormat.from(targetFormat)
                .orElseThrow(() -> new IllegalArgumentException("暂不支持的目标格式：" + targetFormat));
        List<UploadPayload> uploads = files.stream().map(file ->
                new UploadPayload(file.getOriginalFilename(), file.getContentType(), file.getSize(), file::getInputStream)).toList();
        ConversionOptions options = ConversionOptions.fromRequest(compressionMode, watermarkText,
                watermarkOpacity, watermarkAngle, watermarkPosition, watermarkTiled, watermarkPages, watermarkColor,
                splitPages, imageDpi);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(tasks.createTask(uploads, target, options));
    }

    @GetMapping("/capabilities")
    public List<ConversionRoute> capabilities() { return tasks.supportedConversions(); }

    @GetMapping
    public List<TaskSnapshot> list(@RequestParam(defaultValue = "50") int limit) {
        return tasks.listTasks(limit);
    }

    @GetMapping("/{taskId}")
    public TaskSnapshot status(@PathVariable String taskId) { return tasks.get(taskId); }

    @PostMapping("/{taskId}/cancel")
    public TaskSnapshot cancel(@PathVariable String taskId) { return tasks.cancel(taskId); }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<TaskSnapshot> retry(@PathVariable String taskId) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(tasks.retry(taskId));
    }

    @GetMapping("/{taskId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String taskId) {
        DownloadLease lease = tasks.acquireDownload(taskId);
        DownloadArtifact artifact = lease.artifact();
        StreamingResponseBody body = output -> {
            try (lease; var input = Files.newInputStream(artifact.path())) {
                input.transferTo(output);
            }
        };
        try {
            return downloadHeaders(artifact).body(body);
        } catch (RuntimeException | Error error) {
            lease.close();
            throw error;
        }
    }

    @RequestMapping(value = "/{taskId}/download", method = RequestMethod.HEAD)
    public ResponseEntity<Void> downloadHead(@PathVariable String taskId) {
        try (DownloadLease lease = tasks.acquireDownload(taskId)) {
            return downloadHeaders(lease.artifact()).build();
        }
    }

    private ResponseEntity.BodyBuilder downloadHeaders(DownloadArtifact artifact) {
        ContentDisposition disposition = ContentDisposition.attachment().filename(artifact.fileName(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(artifact.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .contentLength(artifact.path().toFile().length());
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable String taskId) {
        tasks.delete(taskId);
        return ResponseEntity.noContent().build();
    }
}
