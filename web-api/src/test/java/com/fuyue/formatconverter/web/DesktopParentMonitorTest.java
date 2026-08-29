package com.fuyue.formatconverter.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class DesktopParentMonitorTest {
    @Test void closesBackendWhenDesktopParentIsMissing() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        FormatConverterProperties properties = new FormatConverterProperties();
        properties.setDesktopParentPid(Long.MAX_VALUE);
        DesktopParentMonitor monitor = new DesktopParentMonitor(context, properties);

        monitor.start();

        verify(context, timeout(2_000)).close();
        monitor.stop();
    }
}
