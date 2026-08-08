package com.fuyue.formatconverter.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Component
public class BrowserLauncher {
    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);
    private final FormatConverterProperties properties;
    private final Environment environment;

    public BrowserLauncher(FormatConverterProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser() {
        if (!properties.isAutoOpenBrowser()) return;
        String url = localUrl();
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1200);
                open(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("无法自动打开浏览器，请手动访问 {}", url);
            }
        });
    }

    private String localUrl() {
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            contextPath = "";
        }
        return "http://127.0.0.1:" + port + contextPath;
    }

    private void open(String url) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder builder;
        if (os.contains("win")) {
            builder = new ProcessBuilder("cmd", "/c", "start", "", url);
        } else if (os.contains("mac")) {
            builder = new ProcessBuilder("open", url);
        } else {
            builder = new ProcessBuilder("xdg-open", url);
        }
        builder.redirectErrorStream(true);
        builder.start();
    }
}
