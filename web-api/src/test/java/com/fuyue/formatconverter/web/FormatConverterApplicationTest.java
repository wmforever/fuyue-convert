package com.fuyue.formatconverter.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "format-converter.data-root=target/test-data",
        "spring.main.banner-mode=off",
        "logging.level.root=WARN",
        "logging.level.com.fuyue.formatconverter=WARN"
})
@AutoConfigureMockMvc
class FormatConverterApplicationTest {
    @Autowired MockMvc mvc;

    @Test void healthEndpointReturnsVersionAndPlatform() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.parser").value("OFDRW 2.3.9"))
                .andExpect(jsonPath("$.arch").isNotEmpty())
                .andExpect(jsonPath("$.office.available").isBoolean())
                .andExpect(jsonPath("$.ocr.enabled").isBoolean())
                .andExpect(jsonPath("$.ocr.available").isBoolean())
                .andExpect(jsonPath("$.ocr.availableLanguages").isArray())
                .andExpect(jsonPath("$.ocr.message").isNotEmpty())
                .andExpect(jsonPath("$.ocr.binary").doesNotExist())
                .andExpect(jsonPath("$.office.binary").doesNotExist());
    }

    @Test void capabilitiesEndpointReturnsRegisteredRoutes() throws Exception {
        mvc.perform(get("/api/tasks/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ofd-to-docx"))
                .andExpect(jsonPath("$[0].sourceFormat").value("ofd"))
                .andExpect(jsonPath("$[0].targetFormat").value("docx"))
                .andExpect(jsonPath("$[0].status").value("available"))
                .andExpect(jsonPath("$[1].id").value("ofd-to-txt"))
                .andExpect(jsonPath("$[1].targetFormat").value("txt"))
                .andExpect(jsonPath("$[2].id").value("ofd-to-pdf"))
                .andExpect(jsonPath("$[3].id").value("ofd-to-png"))
                .andExpect(jsonPath("$[4].id").value("ofd-to-jpg"))
                .andExpect(jsonPath("$[5].id").value("ofd-to-xlsx"))
                .andExpect(jsonPath("$[6].id").value("csv-to-xlsx"))
                .andExpect(jsonPath("$[7].id").value("xlsx-to-csv"))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-docx')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-docx')].qualityLevel").value(contains("beta")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-docx')].strategy").value(contains("editable")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-txt')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-txt')].qualityLevel").value(contains("beta")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-txt')].strategy").value(contains("extraction")))
                .andExpect(jsonPath("$[?(@.id=='docx-to-txt')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='docx-to-txt')].qualityLevel").value(contains("beta")))
                .andExpect(jsonPath("$[?(@.id=='docx-to-txt')].strategy").value(contains("extraction")))
                .andExpect(jsonPath("$[?(@.id=='csv-to-xlsx')].strategy").value(contains("data")))
                .andExpect(jsonPath("$[?(@.id=='png-to-pdf')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='png-to-pdf')].qualityLevel").value(contains("stable")))
                .andExpect(jsonPath("$[?(@.id=='png-to-pdf')].strategy").value(contains("fidelity")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-jpg')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-ofd')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-ofd')].qualityLevel").value(contains("experimental")))
                .andExpect(jsonPath("$[?(@.id=='pdf-to-ofd')].strategy").value(contains("fidelity")))
                .andExpect(jsonPath("$[?(@.id=='ofd-to-xlsx')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='ofd-to-xlsx')].qualityLevel").value(contains("experimental")))
                .andExpect(jsonPath("$[?(@.id=='ofd-to-xlsx')].strategy").value(contains("data")))
                .andExpect(jsonPath("$[?(@.id=='ofd-to-png')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='ofd-to-png')].qualityLevel").value(contains("beta")))
                .andExpect(jsonPath("$[?(@.id=='ofd-to-jpg')].status").value(contains("available")))
                .andExpect(jsonPath("$[?(@.id=='ofd-to-jpg')].qualityLevel").value(contains("beta")));
    }

    @Test void taskHistoryEndpointReturnsLocalList() throws Exception {
        mvc.perform(get("/api/tasks").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/tasks").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("任务记录数量必须在 1 到 100 之间"));
    }

    @Test void diagnosticsEndpointReturnsRedactedEnvironment() throws Exception {
        mvc.perform(get("/api/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.1.3"))
                .andExpect(jsonPath("$.runtime.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.office.available").isBoolean())
                .andExpect(jsonPath("$.ocr.enabled").isBoolean())
                .andExpect(jsonPath("$.ocr.available").isBoolean())
                .andExpect(jsonPath("$.ocr.availableLanguages").isArray())
                .andExpect(jsonPath("$.ocr.message").isNotEmpty())
                .andExpect(jsonPath("$.limits.maxFileSize").isNumber())
                .andExpect(jsonPath("$.limits.maxFilesPerTask").value(100))
                .andExpect(jsonPath("$.limits.maxTaskUploadBytes").isNumber())
                .andExpect(jsonPath("$.limits.maxTaskOutputBytes").isNumber())
                .andExpect(jsonPath("$.limits.minFreeDiskBytes").isNumber())
                .andExpect(jsonPath("$.limits.resultTtl").isNotEmpty())
                .andExpect(jsonPath("$.limits.workerEnabled").value(true))
                .andExpect(jsonPath("$.limits.workerMaxMemoryMb").value(768))
                .andExpect(jsonPath("$.routes[?(@.id=='ofd-to-docx')].status").value(contains("available")))
                .andExpect(jsonPath("$.routes[?(@.id=='ofd-to-docx')].qualityLevel").value(contains("beta")))
                .andExpect(jsonPath("$.apiToken").doesNotExist())
                .andExpect(jsonPath("$.dataRoot").doesNotExist());
    }

    @Test void rejectsInvalidPdfUtilityOptionsBeforeStartingTask() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "input.pdf", "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        mvc.perform(multipart("/api/tasks").file(file)
                        .param("targetFormat", "pdf-watermark")
                        .param("watermarkOpacity", "0.99")
                        .param("watermarkPages", "0-2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("水印不透明度必须在 0.05 到 0.85 之间"));

        mvc.perform(multipart("/api/tasks").file(file)
                        .param("targetFormat", "pdf-split")
                        .param("splitPages", "3-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("拆分页码范围起始页不能大于结束页"));
    }
}
