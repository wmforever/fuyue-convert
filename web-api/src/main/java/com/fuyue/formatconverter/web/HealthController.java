package com.fuyue.formatconverter.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    @Value("${build.version:0.1.1}") private String version;
    private final OfficeEngineStatus officeEngineStatus;
    private final OcrEngineStatus ocrEngineStatus;

    public HealthController(OfficeEngineStatus officeEngineStatus, OcrEngineStatus ocrEngineStatus) {
        this.officeEngineStatus = officeEngineStatus;
        this.ocrEngineStatus = ocrEngineStatus;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("version", version);
        result.put("parser", "OFDRW 2.3.9");
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("os", System.getProperty("os.name"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("office", office());
        result.put("ocr", ocr());
        result.put("time", Instant.now());
        return result;
    }

    private Map<String, Object> ocr() {
        Map<String, Object> ocr = new LinkedHashMap<>();
        ocr.put("enabled", ocrEngineStatus.enabled());
        ocr.put("available", ocrEngineStatus.available());
        ocr.put("binaryName", ocrEngineStatus.binaryName());
        ocr.put("version", ocrEngineStatus.version());
        ocr.put("languages", ocrEngineStatus.languages());
        ocr.put("message", ocrEngineStatus.message());
        return ocr;
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
