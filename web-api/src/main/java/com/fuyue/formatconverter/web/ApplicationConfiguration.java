package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.task.*;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class ApplicationConfiguration {
    @Bean
    FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(FormatConverterProperties properties,
                                                           Environment environment) {
        String serverAddress = environment.getProperty("server.address", "");
        validateNetworkExposure(properties, serverAddress);
        validateDesktopMode(properties, serverAddress);
        FilterRegistrationBean<ApiTokenFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ApiTokenFilter(properties.getApiToken()));
        bean.addUrlPatterns("/api/tasks", "/api/tasks/*", "/api/desktop", "/api/desktop/*");
        bean.setOrder(1);
        return bean;
    }

    static void validateNetworkExposure(FormatConverterProperties properties, String serverAddress) {
        if (isLoopbackAddress(serverAddress)) return;
        String token = properties.getApiToken();
        if (token != null && token.strip().length() >= 32 && token.equals(token.strip())) return;
        if (properties.isAllowInsecureRemote()) return;
        throw new IllegalStateException(
                "非回环监听必须配置至少 32 个字符且不含首尾空白的 FORMAT_CONVERTER_API_TOKEN；"
                        + "仅在外层网络已严格隔离时才可显式设置 FORMAT_CONVERTER_ALLOW_INSECURE_REMOTE=true");
    }

    static void validateDesktopMode(FormatConverterProperties properties, String serverAddress) {
        if (!properties.isDesktopMode()) return;
        String token = properties.getApiToken();
        if (token == null || token.strip().length() < 32 || !token.equals(token.strip())) {
            throw new IllegalStateException("桌面模式必须配置至少 32 个字符的 API Token");
        }
        if (properties.getDesktopParentPid() <= 0) {
            throw new IllegalStateException("桌面模式必须配置有效的父进程 PID");
        }
        if (!isLoopbackAddress(serverAddress)) {
            throw new IllegalStateException("桌面模式仅允许监听回环地址");
        }
    }

    private static boolean isLoopbackAddress(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) return false;
        try {
            return InetAddress.getByName(serverAddress.strip()).isLoopbackAddress();
        } catch (IOException invalidAddress) {
            throw new IllegalStateException("无法解析服务监听地址：" + serverAddress, invalidAddress);
        }
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

    @Bean
    OcrEngineStatus ocrEngineStatus() {
        return OcrEngineStatus.detect();
    }

    @Bean(destroyMethod = "close")
    ConversionTaskService conversionTaskService(FormatConverterProperties properties,
                                                OfficeEngineStatus officeEngineStatus) throws IOException {
        TaskServiceConfig config = new TaskServiceConfig(properties.getDataRoot(), properties.getConcurrency(),
                properties.getQueueCapacity(), properties.getTimeout(), properties.getResultTtl(),
                properties.getMaxFilesPerTask(), properties.getMaxTaskUploadBytes(),
                properties.getMaxTaskOutputBytes(), properties.getMinFreeDiskBytes(), properties.parseLimits());
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
