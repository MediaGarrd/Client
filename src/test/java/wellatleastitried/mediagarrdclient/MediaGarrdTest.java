package wellatleastitried.mediagarrdclient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mediagarrd.client.state-file", () -> dataDir.resolve("state.json").toString());
        registry.add("mediagarrd.client.download-directory", () -> dataDir.resolve("downloads").toString());
        registry.add("mediagarrd.client.preferred-server-ip", () -> "");
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
}
