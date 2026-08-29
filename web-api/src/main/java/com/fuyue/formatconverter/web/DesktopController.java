package com.fuyue.formatconverter.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/desktop")
@ConditionalOnProperty(name = "format-converter.desktop-mode", havingValue = "true")
public class DesktopController {
    private final ConfigurableApplicationContext context;
    private final FormatConverterProperties properties;
    private final AtomicBoolean closing = new AtomicBoolean();

    public DesktopController(ConfigurableApplicationContext context, FormatConverterProperties properties) {
        this.context = context;
        this.properties = properties;
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, String>> shutdown() {
        if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "token-required"));
        }
        if (closing.compareAndSet(false, true)) {
            Thread shutdown = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                context.close();
            }, "desktop-shutdown");
            shutdown.setDaemon(false);
            shutdown.start();
        }
        return ResponseEntity.accepted().body(Map.of("status", "shutting-down"));
    }
}
