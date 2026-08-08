package com.fuyue.formatconverter.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    @Value("${build.version:0.1.1}") private String version;
    private final OfficeEngineStatus officeEngineStatus;

    public HealthController(OfficeEngineStatus officeEngineStatus) {
        this.officeEngineStatus = officeEngineStatus;
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
        result.put("office", officeEngineStatus);
        result.put("time", Instant.now());
        return result;
    }
}
