package com.fuyue.formatconverter.web;

import com.fuyue.formatconverter.docx.PoiDocxRenderer;
import com.fuyue.formatconverter.parser.OfdrwParser;
import com.fuyue.formatconverter.parser.SafeOfdExtractor;
import com.fuyue.formatconverter.table.PageLayoutAnalyzer;
import com.fuyue.formatconverter.task.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
        return LibreOfficeConverter.discover(properties.getOfficeBinary())
                .map(path -> OfficeEngineStatus.available(path.toString()))
                .orElseGet(OfficeEngineStatus::unavailable);
    }

    @Bean(destroyMethod = "close")
    ConversionTaskService conversionTaskService(FormatConverterProperties properties,
                                                OfficeEngineStatus officeEngineStatus) throws IOException {
        TaskServiceConfig config = new TaskServiceConfig(properties.getDataRoot(), properties.getConcurrency(),
                properties.getQueueCapacity(), properties.getTimeout(), properties.getResultTtl(), properties.parseLimits());
        SafeOfdExtractor extractor = new SafeOfdExtractor();
        OfdrwParser parser = new OfdrwParser();
        PageLayoutAnalyzer analyzer = new PageLayoutAnalyzer();
        List<FileConverter> converters = new ArrayList<>();
        converters.add(new OfdToDocxConverter(extractor, parser, analyzer, new PoiDocxRenderer()));
        converters.add(new OfdToTextConverter(extractor, parser, analyzer));
        converters.add(new OfdToPdfConverter(extractor, parser, analyzer));
        converters.add(new CsvToXlsxConverter());
        converters.add(new XlsxToCsvConverter());
        converters.add(new TextToDocxConverter());
        converters.add(new DocxToTextConverter());
        converters.add(new TextToPdfConverter());
        converters.add(new PdfToTextConverter());
        converters.add(new PdfToDocxConverter());
        converters.add(new PdfToPngConverter());
        converters.add(new PdfToJpgConverter());

        if (officeEngineStatus.available()) {
            converters.addAll(officeConverters(Path.of(officeEngineStatus.binary()), properties));
        }
        if (!officeEngineStatus.available()) {
            converters.add(new XlsxToPdfConverter());
            converters.add(new DocxToPdfConverter());
            converters.add(new ImageToPdfConverter(DocumentFormat.PNG));
            converters.add(new ImageToPdfConverter(DocumentFormat.JPG));
        }
        return new ConversionTaskService(config, converters);
    }

    private List<FileConverter> officeConverters(Path binary, FormatConverterProperties properties) {
        return List.of(
                new LibreOfficeConverter(DocumentFormat.DOCX, DocumentFormat.PDF, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 DOCX 高保真导出为 PDF。"),
                new LibreOfficeConverter(DocumentFormat.XLSX, DocumentFormat.PDF, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 XLSX 高保真导出为 PDF。"),
                new LibreOfficeConverter(DocumentFormat.PPTX, DocumentFormat.PDF, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 PPTX 高保真导出为 PDF。"),
                new LibreOfficeConverter(DocumentFormat.WPS, DocumentFormat.DOCX, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 WPS 文字文档转换为 DOCX。"),
                new LibreOfficeConverter(DocumentFormat.ET, DocumentFormat.XLSX, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 WPS 表格 ET 转换为 XLSX。"),
                new LibreOfficeConverter(DocumentFormat.DPS, DocumentFormat.PPTX, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 WPS 演示 DPS 转换为 PPTX。"),
                new OfficeToPdfBackedDocxConverter(DocumentFormat.UOF, binary, properties.getOfficeTimeout(),
                        "将 UOF 先渲染为 PDF，再生成保真优先 DOCX，避免 UOF 直接转 DOCX 时分页漂移。"),
                new LibreOfficeConverter(DocumentFormat.PNG, DocumentFormat.PDF, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 PNG 图片转换为 PDF。"),
                new LibreOfficeConverter(DocumentFormat.JPG, DocumentFormat.PDF, binary, properties.getOfficeTimeout(),
                        "使用 LibreOffice headless 将 JPEG 图片转换为 PDF。")
        );
    }
}
