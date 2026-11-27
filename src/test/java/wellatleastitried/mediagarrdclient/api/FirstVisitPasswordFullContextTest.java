package wellatleastitried.mediagarrdclient.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@org.springframework.context.annotation.Import(wellatleastitried.mediagarrdclient.api.TestCsrfHeader.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstVisitPasswordFullContextTest {

    @TempDir
    static Path dataDir;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mediagarrd.client.state-file", () -> dataDir.resolve("state.json").toString());
        registry.add("mediagarrd.client.download-directory", () -> dataDir.resolve("downloads").toString());
        registry.add("mediagarrd.client.preferred-server-ip", () -> "");
        registry.add("mediagarrd.client.github-api-base-url", () -> "http://127.0.0.1:1");
        registry.add("mediagarrd.client.password", () -> "");
    }

    private static String body(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    @Test
    @Order(1)
    void withoutAPasswordTheDashboardOffersToSetOneAndLoginIsRefused() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
            .andExpect(jsonPath("$.passwordSet").value(false))
            .andExpect(jsonPath("$.passwordFromEnvironment").value(false))
            .andExpect(jsonPath("$.authenticated").value(false));
        mockMvc.perform(get("/api/client/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("anything at all")))
            .andExpect(status().isConflict());
    }

    @Test
    @Order(2)
    void aCrossSiteRequestCannotSetThePasswordAndAShortOneIsRefused() throws Exception {
        mockMvc.perform(post("/api/auth/set-password").header("Origin", "http://evil.example.com").header("Host", "client.local:8081")
                .contentType(MediaType.APPLICATION_JSON).content(body("long enough password")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/set-password").contentType(MediaType.APPLICATION_JSON).content(body("short")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get("/api/auth/status")).andExpect(jsonPath("$.passwordSet").value(false));
    }

    @Test
    @Order(3)
    void theFirstVisitorSetsThePasswordAndIsSignedIn() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/set-password").contentType(MediaType.APPLICATION_JSON).content(body("my first password")))
            .andExpect(status().isOk())
            .andReturn();
        Cookie cookie = result.getResponse().getCookie("mg_session");

        mockMvc.perform(get("/api/client/status").cookie(cookie)).andExpect(status().isOk());
        Path file = dataDir.resolve("password.json");
        assertTrue(Files.exists(file));
        assertFalse(Files.readString(file).contains("my first password"));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    @Order(4)
    void theSecondVisitorCannotReplaceThePasswordButCanLogInWithIt() throws Exception {
        mockMvc.perform(post("/api/auth/set-password").contentType(MediaType.APPLICATION_JSON).content(body("an attacker password")))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("an attacker password")))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("my first password")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/status")).andExpect(jsonPath("$.passwordSet").value(true));
    }
}
