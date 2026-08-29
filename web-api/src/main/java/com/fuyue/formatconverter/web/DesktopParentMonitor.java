package com.fuyue.formatconverter.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "format-converter.desktop-mode", havingValue = "true")
public class DesktopParentMonitor {
    private final ConfigurableApplicationContext context;
    private final FormatConverterProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public DesktopParentMonitor(ConfigurableApplicationContext context, FormatConverterProperties properties) {
        this.context = context;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long parentPid = properties.getDesktopParentPid();
        if (parentPid <= 0 || !running.compareAndSet(false, true)) return;
        Thread monitor = new Thread(() -> monitor(parentPid), "desktop-parent-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private void monitor(long parentPid) {
        while (running.get()) {
            boolean parentAlive = ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);
            if (!parentAlive) {
                running.set(false);
                context.close();
                return;
            }
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        running.set(false);
    }
}
