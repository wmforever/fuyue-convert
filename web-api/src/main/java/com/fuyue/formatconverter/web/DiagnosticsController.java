package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.ConversionRoute;
import com.fuyue.formatconverter.task.ConversionTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DiagnosticsController {
    @Value("${build.version:0.1.1}") private String version;
    private final FormatConverterProperties properties;
    private final OfficeEngineStatus officeEngineStatus;
    private final OcrEngineStatus ocrEngineStatus;
    private final ConversionTaskService tasks;

    public DiagnosticsController(FormatConverterProperties properties,
                                 OfficeEngineStatus officeEngineStatus,
                                 OcrEngineStatus ocrEngineStatus,
                                 ConversionTaskService tasks) {
        this.properties = properties;
        this.officeEngineStatus = officeEngineStatus;
        this.ocrEngineStatus = ocrEngineStatus;
        this.tasks = tasks;
    }

    @GetMapping("/api/diagnostics")
    public Map<String, Object> diagnostics() {
        List<ConversionRoute> routes = tasks.supportedConversions();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("time", Instant.now());
        result.put("runtime", runtime());
        result.put("office", office());
        result.put("ocr", ocr());
        result.put("limits", limits());
        result.put("routes", routes.stream().map(this::route).toList());
        return result;
    }

    private Map<String, Object> ocr() {
        Map<String, Object> ocr = new LinkedHashMap<>();
        ocr.put("enabled", ocrEngineStatus.enabled());
        ocr.put("available", ocrEngineStatus.available());
        ocr.put("binaryName", ocrEngineStatus.binaryName());
        ocr.put("version", ocrEngineStatus.version());
        ocr.put("requestedLanguages", ocrEngineStatus.requestedLanguages());
        ocr.put("availableLanguages", ocrEngineStatus.availableLanguages());
        ocr.put("timeoutSeconds", ocrEngineStatus.timeoutSeconds());
        ocr.put("maxConcurrency", ocrEngineStatus.maxConcurrency());
        ocr.put("maxImagePixels", ocrEngineStatus.maxImagePixels());
        ocr.put("minimumConfidence", ocrEngineStatus.minimumConfidence());
        ocr.put("bundled", ocrEngineStatus.bundled());
        ocr.put("errorCode", ocrEngineStatus.errorCode());
        ocr.put("message", ocrEngineStatus.message());
        return ocr;
    }

    private Map<String, Object> runtime() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("os", System.getProperty("os.name"));
        runtime.put("arch", System.getProperty("os.arch"));
        runtime.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        return runtime;
    }

    private Map<String, Object> office() {
        Map<String, Object> office = new LinkedHashMap<>();
        office.put("enabled", officeEngineStatus.enabled());
        office.put("available", officeEngineStatus.available());
        office.put("binaryName", binaryName(officeEngineStatus.binary()));
        office.put("version", officeEngineStatus.version());
        office.put("message", officeEngineStatus.message());
        return office;
    }

    private Map<String, Object> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("concurrency", properties.getConcurrency());
        limits.put("queueCapacity", properties.getQueueCapacity());
        limits.put("timeout", properties.getTimeout().toString());
        limits.put("maxFilesPerTask", properties.getMaxFilesPerTask());
        limits.put("maxFileSize", properties.getMaxFileSize());
        limits.put("maxTaskUploadBytes", properties.getMaxTaskUploadBytes());
        limits.put("maxTaskOutputBytes", properties.getMaxTaskOutputBytes());
        limits.put("minFreeDiskBytes", properties.getMinFreeDiskBytes());
        limits.put("resultTtl", properties.getResultTtl().toString());
        limits.put("maxPages", properties.getMaxPages());
        limits.put("officeTimeout", properties.getOfficeTimeout().toString());
        limits.put("workerEnabled", properties.isWorkerEnabled());
        limits.put("workerMaxMemoryMb", properties.getWorkerMaxMemoryMb());
        return limits;
    }

    private Map<String, Object> route(ConversionRoute route) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", route.id());
        item.put("sourceFormat", route.sourceFormat());
        item.put("targetFormat", route.targetFormat());
        item.put("status", route.status());
        item.put("qualityLevel", route.qualityLevel());
        item.put("strategy", route.strategy());
        item.put("requires", route.requires());
        item.put("limitations", route.limitations());
        item.put("description", route.description());
        return item;
    }

    private String binaryName(String binary) {
        if (binary == null || binary.isBlank()) return null;
        try {
            Path fileName = Path.of(binary).getFileName();
            return fileName == null ? null : fileName.toString();
        } catch (RuntimeException e) {
            return "configured";
        }
    }
}
