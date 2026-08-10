package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrEnabledRegistryIntegrationTest {
    @Test
    void enabledDeploymentRegistersImageTextAndEditableWordRoutes() {
        var capability = TesseractOcrConverter.detectConfigured();
        assumeTrue(capability.enabled() && capability.available(),
                "Run with FORMAT_CONVERTER_OCR_ENABLED=true and installed models");

        var routes = DefaultConverterRegistry.create(null, Duration.ofMinutes(1)).stream()
                .map(FileConverter::route).toList();

        assertTrue(hasRoute(routes, DocumentFormat.PNG, DocumentFormat.TXT));
        assertTrue(hasRoute(routes, DocumentFormat.JPG, DocumentFormat.TXT));
        assertTrue(hasRoute(routes, DocumentFormat.PNG, DocumentFormat.DOCX));
        assertTrue(hasRoute(routes, DocumentFormat.JPG, DocumentFormat.DOCX));
    }

    private boolean hasRoute(java.util.List<ConversionRoute> routes, DocumentFormat source,
                             DocumentFormat target) {
        return routes.stream().anyMatch(route -> route.sourceFormat() == source && route.targetFormat() == target);
    }
}
