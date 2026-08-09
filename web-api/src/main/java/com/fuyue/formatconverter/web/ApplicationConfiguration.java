package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.*;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class ApplicationConfiguration {
    @Bean
    FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(FormatConverterProperties properties) {
        FilterRegistrationBean<ApiTokenFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ApiTokenFilter(properties.getApiToken()));
        bean.addUrlPatterns("/api/tasks", "/api/tasks/*");
        bean.setOrder(1);
        return bean;
    }

    @Bean
    OfficeEngineStatus officeEngineStatus(FormatConverterProperties properties) {
        if (!properties.isOfficeEnabled()) return OfficeEngineStatus.disabled();
        var discovered = LibreOfficeConverter.discover(properties.getOfficeBinary());
        if (discovered.isEmpty()) return OfficeEngineStatus.unavailable();
        Path binary = discovered.orElseThrow();
        String version = LibreOfficeConverter.version(binary).orElse("unknown");
        String required = properties.getOfficeRequiredVersion();
        if (required != null && !required.isBlank() && !version.contains(required.strip())) {
            return OfficeEngineStatus.incompatible(version, required.strip());
        }
        return OfficeEngineStatus.available(binary.toString(), version);
    }

    @Bean(destroyMethod = "close")
    ConversionTaskService conversionTaskService(FormatConverterProperties properties,
                                                OfficeEngineStatus officeEngineStatus) throws IOException {
        TaskServiceConfig config = new TaskServiceConfig(properties.getDataRoot(), properties.getConcurrency(),
                properties.getQueueCapacity(), properties.getTimeout(), properties.getResultTtl(), properties.parseLimits());
        Path officeBinary = officeEngineStatus.available() ? Path.of(officeEngineStatus.binary()) : null;
        List<FileConverter> converters = DefaultConverterRegistry.create(officeBinary, properties.getOfficeTimeout());
        if (properties.isWorkerEnabled()) {
            List<String> command = workerCommand(properties);
            String office = officeBinary == null ? "" : officeBinary.toAbsolutePath().normalize().toString();
            converters = converters.stream()
                    .map(converter -> (FileConverter) new ForkedFileConverter(
                            converter.route(), command, office, properties.getOfficeTimeout()))
                    .toList();
        }
        return new ConversionTaskService(config, converters);
    }

    private List<String> workerCommand(FormatConverterProperties properties) throws IOException {
        Path java = properties.getWorkerJavaBinary() == null || properties.getWorkerJavaBinary().isBlank()
                ? Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                : Path.of(properties.getWorkerJavaBinary());
        if (!java.toFile().isFile()) throw new IOException("Worker Java 不存在：" + java);
        String heap = "-Xmx" + properties.getWorkerMaxMemoryMb() + "m";
        File source = new ApplicationHome(FormatConverterApplication.class).getSource();
        if (source != null && source.isFile() && source.getName().endsWith(".jar")) {
            return List.of(java.toString(), heap,
                    "-Dloader.main=" + ConversionWorkerMain.class.getName(),
                    "-cp", source.getAbsolutePath(),
                    "org.springframework.boot.loader.launch.PropertiesLauncher");
        }
        return List.of(java.toString(), heap, "-cp", System.getProperty("java.class.path"),
                ConversionWorkerMain.class.getName());
    }

    private boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
}
