package com.fuyue.formatconverter.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopModeConfigurationTest {
    @Test void ignoresDesktopGuardForRegularWebMode() {
        assertDoesNotThrow(() -> ApplicationConfiguration.validateDesktopMode(
                new FormatConverterProperties(), "0.0.0.0"));
    }

    @Test void requiresStrongTokenInDesktopMode() {
        FormatConverterProperties properties = desktopProperties();
        properties.setApiToken("short");

        assertThrows(IllegalStateException.class,
                () -> ApplicationConfiguration.validateDesktopMode(properties, "127.0.0.1"));

        properties.setApiToken(" ".repeat(32));
        assertThrows(IllegalStateException.class,
                () -> ApplicationConfiguration.validateDesktopMode(properties, "127.0.0.1"));

        properties.setApiToken(" " + "a".repeat(32));
        assertThrows(IllegalStateException.class,
                () -> ApplicationConfiguration.validateDesktopMode(properties, "127.0.0.1"));
    }

    @Test void requiresDesktopParentProcess() {
        FormatConverterProperties properties = desktopProperties();
        properties.setDesktopParentPid(-1);

        assertThrows(IllegalStateException.class,
                () -> ApplicationConfiguration.validateDesktopMode(properties, "127.0.0.1"));
    }

    @Test void refusesNonLoopbackDesktopBinding() {
        FormatConverterProperties properties = desktopProperties();

        assertThrows(IllegalStateException.class,
                () -> ApplicationConfiguration.validateDesktopMode(properties, "0.0.0.0"));
    }

    @Test void acceptsLoopbackDesktopConfiguration() {
        assertDoesNotThrow(() -> ApplicationConfiguration.validateDesktopMode(
                desktopProperties(), "localhost"));
        assertDoesNotThrow(() -> ApplicationConfiguration.validateDesktopMode(
                desktopProperties(), "::1"));
    }

    @Test void refusesMissingDesktopBinding() {
        assertThrows(IllegalStateException.class,
                () -> ApplicationConfiguration.validateDesktopMode(desktopProperties(), ""));
    }

    private FormatConverterProperties desktopProperties() {
        FormatConverterProperties properties = new FormatConverterProperties();
        properties.setDesktopMode(true);
        properties.setDesktopParentPid(1234);
        properties.setApiToken("a".repeat(32));
        return properties;
    }
}
