package cn.tensafe.ofd2word.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "ofd2word.data-root=target/test-data",
        "spring.main.banner-mode=off",
        "logging.level.root=WARN",
        "logging.level.cn.tensafe.ofd2word=WARN"
})
@AutoConfigureMockMvc
class OfdToWordApplicationTest {
    @Autowired MockMvc mvc;

    @Test void healthEndpointReturnsVersionAndPlatform() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.parser").value("OFDRW 2.3.9"))
                .andExpect(jsonPath("$.arch").isNotEmpty());
    }
}
