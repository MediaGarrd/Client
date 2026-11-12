package wellatleastitried.mediagarrdclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MediaGarrdTest {

    @TempDir
    static Path dataDir;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Configuration configuration;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mediagarrd.client.state-file", () -> dataDir.resolve("state.json").toString());
        registry.add("mediagarrd.client.download-directory", () -> dataDir.resolve("downloads").toString());
        registry.add("mediagarrd.client.preferred-server-ip", () -> "");
        registry.add("mediagarrd.client.github-api-base-url", () -> "http://127.0.0.1:1");
    }

    @Test
    void bindsTheBackupRunTimeoutDefault() {
        assertEquals(Duration.ofHours(6), configuration.getBackupRunTimeout());
    }

    @Test
    void servesTheWebPageAndItsAssets() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/css/mediagarrd.css"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/js/main.js")).andExpect(status().isOk());
    }

    @Test
    void reportsStatusWithoutAServer() throws Exception {
        mockMvc.perform(get("/api/client/status"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"activeServer\":null,\"knownServers\":[]}"));
    }

    @Test
    void reportsTheDashboardWithTheClientVersionAndNoServer() throws Exception {
        mockMvc.perform(get("/api/client/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.link").value("DOWN"))
            .andExpect(jsonPath("$.activeServer").doesNotExist())
            .andExpect(jsonPath("$.versions.client.checkState").exists())
            .andExpect(jsonPath("$.versions.server").doesNotExist());
    }
}
