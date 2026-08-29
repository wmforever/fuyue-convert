package com.fuyue.formatconverter.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DesktopControllerTest {
    @Test void refusesShutdownWhenDesktopTokenIsBlank() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        FormatConverterProperties properties = new FormatConverterProperties();
        DesktopController controller = new DesktopController(context, properties);

        assertEquals(403, controller.shutdown().getStatusCode().value());
        verifyNoInteractions(context);
    }

    @Test void closesApplicationContextAfterAcceptedShutdown() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        FormatConverterProperties properties = new FormatConverterProperties();
        properties.setApiToken("secret");
        DesktopController controller = new DesktopController(context, properties);

        assertEquals(202, controller.shutdown().getStatusCode().value());
        verify(context, timeout(1_000)).close();
    }
}
