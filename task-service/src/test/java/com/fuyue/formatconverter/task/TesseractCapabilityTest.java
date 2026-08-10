package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TesseractCapabilityTest {
    @TempDir Path temp;

    @Test
    void disabledOcrDoesNotProbeOrRegisterAnError() {
        var capability = TesseractOcrConverter.detectConfigured(Map.of());

        assertFalse(capability.enabled());
        assertFalse(capability.available());
        assertNull(capability.errorCode());
    }

    @Test
    void validBundledRuntimeAutoEnablesWithoutHostInstallation() throws Exception {
        assumePosix();
        Path appHome = temp.resolve("bundled-app");
        Path binary = appHome.resolve("ocr/bin/tesseract");
        Files.createDirectories(binary.getParent());
        Files.createDirectories(appHome.resolve("ocr/tessdata"));
        writeCapabilityBinary(binary, "eng\nchi_sim");

        var capability = TesseractOcrConverter.detectConfigured(Map.of(
                "FORMAT_CONVERTER_APP_HOME", appHome.toString(),
                "FORMAT_CONVERTER_OCR_LANGUAGES", "chi_sim+eng",
                "PATH", temp.resolve("empty-path").toString()));

        assertTrue(capability.enabled());
        assertTrue(capability.available(), capability.message());
        assertTrue(capability.settings().bundled());
        assertEquals(appHome.resolve("ocr/tessdata").toAbsolutePath().normalize(),
                capability.settings().tessdataDirectory());
    }

    @Test
    void explicitFalseDisablesEvenAValidBundledRuntime() throws Exception {
        assumePosix();
        Path appHome = temp.resolve("disabled-bundle");
        Path binary = appHome.resolve("ocr/bin/tesseract");
        Files.createDirectories(binary.getParent());
        Files.createDirectories(appHome.resolve("ocr/tessdata"));
        writeCapabilityBinary(binary, "eng\nchi_sim");

        var capability = TesseractOcrConverter.detectConfigured(Map.of(
                "FORMAT_CONVERTER_APP_HOME", appHome.toString(),
                "FORMAT_CONVERTER_OCR_ENABLED", "false"));

        assertFalse(capability.enabled());
    }

    @Test
    void reportsUnavailableEngineWhenExplicitlyEnabled() {
        var capability = TesseractOcrConverter.detectConfigured(Map.of(
                "FORMAT_CONVERTER_OCR_ENABLED", "true",
                "PATH", temp.toString()));

        assertTrue(capability.enabled());
        assertFalse(capability.available());
        assertEquals("OCR_ENGINE_UNAVAILABLE", capability.errorCode());
    }

    @Test
    void reportsMissingLanguageWithoutLosingDetectedCapabilities() throws Exception {
        assumePosix();
        Path binary = fakeCapabilityBinary("eng\nchi_sim");
        Map<String, String> environment = enabledEnvironment(binary);
        environment.put("FORMAT_CONVERTER_OCR_LANGUAGES", "chi_sim+chi_sim_vert");

        var capability = TesseractOcrConverter.detectConfigured(environment);

        assertFalse(capability.available());
        assertEquals("OCR_LANGUAGE_MISSING", capability.errorCode());
        assertEquals("chi_sim+chi_sim_vert", capability.requestedLanguages());
        assertTrue(capability.availableLanguages().containsAll(java.util.Set.of("eng", "chi_sim")));
        assertTrue(capability.message().contains("chi_sim_vert"));
    }

    @Test
    void exposesValidatedRuntimeLimitsForAvailableEngine() throws Exception {
        assumePosix();
        Path binary = fakeCapabilityBinary("eng\nchi_sim\nchi_sim_vert");
        Map<String, String> environment = enabledEnvironment(binary);
        environment.put("FORMAT_CONVERTER_OCR_LANGUAGES", "chi_sim+eng");
        environment.put("FORMAT_CONVERTER_OCR_TIMEOUT_SECONDS", "45");
        environment.put("FORMAT_CONVERTER_OCR_MAX_CONCURRENCY", "3");
        environment.put("FORMAT_CONVERTER_OCR_MAX_PIXELS", "1234567");
        environment.put("FORMAT_CONVERTER_OCR_MIN_CONFIDENCE", "0.42");
        environment.put("FORMAT_CONVERTER_OCR_WARN_CONFIDENCE", "0.81");
        environment.put("FORMAT_CONVERTER_OCR_LOCK_DIR", temp.resolve("locks").toString());

        var capability = TesseractOcrConverter.detectConfigured(environment);

        assertTrue(capability.available(), capability.message());
        assertEquals(45, capability.settings().timeout().toSeconds());
        assertEquals(3, capability.settings().maxConcurrency());
        assertEquals(1_234_567L, capability.settings().maxImagePixels());
        assertEquals(0.42d, capability.settings().minimumConfidence());
        assertEquals(0.81d, capability.settings().warningConfidence());
    }

    @Test
    void rejectsInvalidThresholdRelationshipWithStableConfigCode() throws Exception {
        assumePosix();
        Path binary = fakeCapabilityBinary("eng");
        Map<String, String> environment = enabledEnvironment(binary);
        environment.put("FORMAT_CONVERTER_OCR_LANGUAGES", "eng");
        environment.put("FORMAT_CONVERTER_OCR_MIN_CONFIDENCE", "0.9");
        environment.put("FORMAT_CONVERTER_OCR_WARN_CONFIDENCE", "0.5");

        var capability = TesseractOcrConverter.detectConfigured(environment);

        assertFalse(capability.available());
        assertEquals("OCR_CONFIG_INVALID", capability.errorCode());
    }

    private Map<String, String> enabledEnvironment(Path binary) {
        Map<String, String> environment = new HashMap<>();
        environment.put("FORMAT_CONVERTER_OCR_ENABLED", "true");
        environment.put("FORMAT_CONVERTER_TESSERACT_BINARY", binary.toString());
        environment.put("PATH", temp.toString());
        return environment;
    }

    private Path fakeCapabilityBinary(String languages) throws Exception {
        Path binary = temp.resolve("tesseract");
        writeCapabilityBinary(binary, languages);
        return binary;
    }

    private void writeCapabilityBinary(Path binary, String languages) throws Exception {
        String script = "#!/bin/sh\n" +
                "if [ \"$1\" = \"--version\" ]; then echo 'tesseract 5.0.0-test'; exit 0; fi\n" +
                "if [ \"$1\" = \"--list-langs\" ]; then printf 'List of available languages:\\n" +
                languages.replace("'", "'\\''") + "\\n'; exit 0; fi\n" +
                "exit 2\n";
        Files.writeString(binary, script);
        assertTrue(binary.toFile().setExecutable(true));
    }

    private void assumePosix() {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }
}
