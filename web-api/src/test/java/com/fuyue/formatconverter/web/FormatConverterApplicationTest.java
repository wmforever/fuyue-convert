package com.fuyue.formatconverter.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test void diagnosticsEndpointReturnsRedactedEnvironment() throws Exception {
        mvc.perform(get("/api/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.1.1"))
                .andExpect(jsonPath("$.runtime.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.office.available").isBoolean())
                .andExpect(jsonPath("$.ocr.enabled").isBoolean())
                .andExpect(jsonPath("$.ocr.available").isBoolean())
                .andExpect(jsonPath("$.ocr.availableLanguages").isArray())
                .andExpect(jsonPath("$.ocr.message").isNotEmpty())
                .andExpect(jsonPath("$.limits.maxFileSize").isNumber())
                .andExpect(jsonPath("$.limits.maxTaskUploadBytes").isNumber())
                .andExpect(jsonPath("$.limits.minFreeDiskBytes").isNumber())
                .andExpect(jsonPath("$.limits.resultTtl").isNotEmpty())
                .andExpect(jsonPath("$.limits.workerEnabled").value(true))
                .andExpect(jsonPath("$.limits.workerMaxMemoryMb").value(768))
                .andExpect(jsonPath("$.routes[?(@.id=='ofd-to-docx')].status").value(contains("available")))
                .andExpect(jsonPath("$.routes[?(@.id=='ofd-to-docx')].qualityLevel").value(contains("beta")))
                .andExpect(jsonPath("$.apiToken").doesNotExist())
                .andExpect(jsonPath("$.dataRoot").doesNotExist());
    }
}
