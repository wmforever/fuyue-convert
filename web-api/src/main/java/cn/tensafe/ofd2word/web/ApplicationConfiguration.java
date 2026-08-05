package cn.tensafe.ofd2word.web;

import cn.tensafe.ofd2word.docx.PoiDocxRenderer;
import cn.tensafe.ofd2word.parser.OfdrwParser;
import cn.tensafe.ofd2word.parser.SafeOfdExtractor;
import cn.tensafe.ofd2word.table.PageLayoutAnalyzer;
import cn.tensafe.ofd2word.task.ConversionTaskService;
import cn.tensafe.ofd2word.task.TaskServiceConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class ApplicationConfiguration {
    @Bean(destroyMethod = "close")
    ConversionTaskService conversionTaskService(OfdToWordProperties properties) throws IOException {
        TaskServiceConfig config = new TaskServiceConfig(properties.getDataRoot(), properties.getConcurrency(),
                properties.getQueueCapacity(), properties.getTimeout(), properties.getResultTtl(), properties.parseLimits());
        return new ConversionTaskService(config, new SafeOfdExtractor(), new OfdrwParser(),
                new PageLayoutAnalyzer(), new PoiDocxRenderer());
    }
}

