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
    void discoversBundledRuntimeFromPackagedApplicationRoot() throws Exception {
        assumePosix();
        Path packageRoot = temp.resolve("runtime-package");
        Path binary = packageRoot.resolve("app/ocr/bin/tesseract");
        Files.createDirectories(binary.getParent());
        Files.createDirectories(packageRoot.resolve("app/ocr/tessdata"));
        writeCapabilityBinary(binary, "eng\nchi_sim");

        var capability = TesseractOcrConverter.detectConfigured(Map.of(
                "FORMAT_CONVERTER_APP_HOME", packageRoot.toString(),
                "FORMAT_CONVERTER_OCR_LANGUAGES", "chi_sim+eng",
                "PATH", temp.resolve("empty-path").toString()));

        assertTrue(capability.available(), capability.message());
        assertTrue(capability.settings().bundled());
    }

    @Test
    void findsJpackageBundleBesideLauncherWithSpacesFromAnUnrelatedWorkingDirectory() throws Exception {
        Path installation = temp.resolve("Program Files/Fuyue Convert");
        Path bundled = installation.resolve("app/ocr");
        Files.createDirectories(bundled);
        Path outside = Files.createDirectories(temp.resolve("unrelated working directory"));
        String oldLauncher = System.getProperty("jpackage.app-path");
        String oldHome = System.getProperty("format.converter.app.home");
        String oldDirectory = System.getProperty("user.dir");
        try {
            System.clearProperty("format.converter.app.home");
            System.setProperty("jpackage.app-path", installation.resolve("FuyueConvert.exe").toString());
            System.setProperty("user.dir", outside.toString());

            assertEquals(bundled.toAbsolutePath().normalize(),
                    TesseractOcrConverter.bundledRoot(Map.of()).orElseThrow(),
                    "jpackage.app-path 是启动器文件路径，必须从其父目录寻找 app/ocr");
            var disabled = TesseractOcrConverter.detectConfigured(Map.of("FORMAT_CONVERTER_OCR_ENABLED", "false"));
            assertFalse(disabled.enabled(), "自动发现安装包不得覆盖用户显式关闭 OCR 的配置");
            assertFalse(disabled.available());
        } finally {
            restoreProperty("jpackage.app-path", oldLauncher);
            restoreProperty("format.converter.app.home", oldHome);
            restoreProperty("user.dir", oldDirectory);
        }
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
        assertEquals(RouteStatus.UNAVAILABLE,
                new UnavailableOcrConverter(DocumentFormat.PNG, DocumentFormat.TXT, capability).route().status());
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

    @Test
    void explicitTessdataDirectoryWithSpacesIsPassedToCapabilityProbesAndRecognitionSettings() throws Exception {
        assumePosix();
        Path binary = temp.resolve("tesseract");
        Path models = Files.createDirectories(temp.resolve("separate language models"));
        String quotedModels = "'" + models.toString().replace("'", "'\\''") + "'";
        Files.writeString(binary, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 'tesseract 5.5.0-test'; exit 0; fi\n"
                + "if [ \"$1\" = \"--list-langs\" ] && [ \"$2\" = \"--tessdata-dir\" ] && [ \"$3\" = " + quotedModels + " ]; then printf 'chi_sim\\neng\\n'; exit 0; fi\n"
                + "exit 2\n");
        assertTrue(binary.toFile().setExecutable(true));
        var environment = enabledEnvironment(binary);
        environment.put("FORMAT_CONVERTER_TESSDATA_DIR", models.toString());
        var capability = TesseractOcrConverter.detectConfigured(environment);
        assertTrue(capability.available(), capability.message());
        assertEquals(models.toAbsolutePath().normalize(), capability.settings().tessdataDirectory());
        assertFalse(capability.settings().bundled());
    }

    @Test
    void missingExplicitTessdataDirectoryReturnsActionableConfigurationFailure() throws Exception {
        assumePosix();
        var environment = enabledEnvironment(fakeCapabilityBinary("chi_sim\neng"));
        environment.put("FORMAT_CONVERTER_TESSDATA_DIR", temp.resolve("missing models").toString());
        var capability = TesseractOcrConverter.detectConfigured(environment);
        assertFalse(capability.available());
        assertEquals("OCR_CONFIG_INVALID", capability.errorCode());
        assertTrue(capability.message().contains("语言包文件夹"));
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

    private void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
