package wellatleastitried.mediagarrdclient.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.servlet.http.Cookie;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;

@SpringBootTest
@org.springframework.context.annotation.Import(wellatleastitried.mediagarrdclient.api.TestCsrfHeader.class)
@AutoConfigureMockMvc
class AuthGateFullContextTest {

    private static final String PASSWORD = "correct horse battery";

    @TempDir
    static Path dataDir;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MediaGarrdProperties configuration;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mediagarrd.client.state-file", () -> dataDir.resolve("state.json").toString());
        registry.add("mediagarrd.client.download-directory", () -> dataDir.resolve("downloads").toString());
        registry.add("mediagarrd.client.preferred-server-ip", () -> "");
        registry.add("mediagarrd.client.github-api-base-url", () -> "http://127.0.0.1:1");
        registry.add("mediagarrd.client.password", () -> PASSWORD);
    }

    private static String loginBody(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private MvcResult login(String source, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(source);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(password)))
            .andReturn();
    }

    private Cookie signIn(String source) throws Exception {
        MvcResult result = login(source, PASSWORD);
        assertEquals(200, result.getResponse().getStatus());
        return result.getResponse().getCookie("mg_session");
    }

    @Test
    void everyRegisteredHandlerRefusesARequestWithoutASessionExceptTheAuthEndpoints() throws Exception {
        Set<String> checkedPublic = new TreeSet<>();
        Set<String> checkedProtected = new TreeSet<>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            boolean open = handler.hasMethodAnnotation(PublicEndpoint.class);
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (HttpMethod method : methods.isEmpty() ? List.of(HttpMethod.GET) : methods.stream().map(m -> HttpMethod.valueOf(m.name())).toList()) {
                    String path = pattern.replaceAll("\\{[^/}]+}", "x");
                    MvcResult result = mockMvc.perform(request(method, URI.create(path))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                        .andReturn();
                    String label = method + " " + path;
                    if (open) {
                        assertFalse(result.getResponse().getContentAsString().contains("\"login\":\"required\""), label + " should be reachable without a session");
                        checkedPublic.add(label);
                    } else {
                        assertEquals(401, result.getResponse().getStatus(), label + " should require a session");
                        assertTrue(result.getResponse().getContentAsString().contains("\"login\":\"required\""), label);
                        checkedProtected.add(label);
                    }
                }
            }
        }

        assertEquals(Set.of("GET /api/auth/status", "POST /api/auth/login", "POST /api/auth/set-password", "POST /api/auth/logout"), checkedPublic);
        assertTrue(checkedProtected.contains("GET /api/client/events"), checkedProtected.toString());
        assertTrue(checkedProtected.contains("POST /api/client/pairing"), checkedProtected.toString());
        assertTrue(checkedProtected.contains("GET /api/client/dashboard"), checkedProtected.toString());
        assertTrue(checkedProtected.stream().noneMatch(label -> label.contains("/api/auth/")), checkedProtected.toString());
    }

    @Test
    void pathVariantsOfAProtectedEndpointNeverBypassTheGate() throws Exception {
        List<String> variants = new ArrayList<>(List.of(
            "/api/client/status;x",
            "/api/client/status;jsessionid=1",
            "/api/client/%73tatus",
            "/api/client/status/",
            "//api/client/status",
            "///api/client/status",
            "/api/client/./status",
            "/api/client/%2e/status",
            "/api/client/x/../status",
            "/api/client/status%2f",
            "/api/client/status%3bx",
            "/API/CLIENT/STATUS",
            "/api;x/client/status",
            "/api/client;x/status",
            "/api/client/status.json",
            "/api/client/status?x=1"));
        for (String variant : variants) {
            MvcResult result = mockMvc.perform(get(URI.create(variant))).andReturn();
            int status = result.getResponse().getStatus();
            assertFalse(status / 100 == 2, variant + " answered " + status + " without a session");
            assertFalse(result.getResponse().getContentAsString().contains("activeServer"), variant);
        }
    }

    @Test
    void aMatrixParameterVariantReachesTheHandlerSoTheGateReallyDecidedIt() throws Exception {
        Cookie cookie = signIn("10.1.0.1");

        mockMvc.perform(get(URI.create("/api/client/status;x")).cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(get(URI.create("/api/client/status;x"))).andExpect(status().isUnauthorized());
    }

    @Test
    void theWebPageAndItsAssetsAreServedWithoutASession() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/js/main.js")).andExpect(status().isOk());
        mockMvc.perform(get("/js/auth.js")).andExpect(status().isOk());
        mockMvc.perform(get("/css/mediagarrd.css"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void bindsTheBackupRunTimeoutDefault() {
        assertEquals(Duration.ofHours(6), configuration.getBackupRunTimeout());
    }

    @Test
    void reportsStatusWithoutAServer() throws Exception {
        mockMvc.perform(get("/api/client/status").cookie(signIn("10.1.0.21")))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"activeServer\":null,\"knownServers\":[]}"));
    }

    @Test
    void reportsTheDashboardWithTheClientVersionAndNoServer() throws Exception {
        mockMvc.perform(get("/api/client/dashboard").cookie(signIn("10.1.0.22")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.link").value("DOWN"))
            .andExpect(jsonPath("$.activeServer").doesNotExist())
            .andExpect(jsonPath("$.versions.client.checkState").exists())
            .andExpect(jsonPath("$.versions.server").doesNotExist())
            .andExpect(jsonPath("$.pairing").value("NONE"));
    }

    @Test
    void reportsWhetherThePasswordIsSetAndWhoComesFromTheEnvironment() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordSet").value(true))
            .andExpect(jsonPath("$.passwordFromEnvironment").value(true))
            .andExpect(jsonPath("$.authenticated").value(false));
        mockMvc.perform(get("/api/auth/status").cookie(signIn("10.1.0.2")))
            .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void aWrongPasswordIsRefusedWithoutTheLoginRequiredMarker() throws Exception {
        MvcResult result = login("10.1.0.3", "wrong password");

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("{\"error\":\"Incorrect password\"}", result.getResponse().getContentAsString());
        assertEquals(null, result.getResponse().getCookie("mg_session"));
    }

    @Test
    void theSessionCookieIsHttpOnlySameSiteStrictAndSecureOnlyOverHttps() throws Exception {
        String plain = login("10.1.0.4", PASSWORD).getResponse().getHeader("Set-Cookie");
        String forwarded = mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr("10.1.0.5");
                    return request;
                })
                .header("X-Forwarded-Proto", "https")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(PASSWORD)))
            .andReturn().getResponse().getHeader("Set-Cookie");
        String direct = mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr("10.1.0.6");
                    request.setSecure(true);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(PASSWORD)))
            .andReturn().getResponse().getHeader("Set-Cookie");

        assertNotNull(plain);
        assertTrue(plain.contains("HttpOnly") && plain.contains("SameSite=Strict") && plain.contains("Path=/"), plain);
        assertFalse(plain.contains("Secure"), plain);
        assertTrue(forwarded.contains("Secure") && forwarded.contains("HttpOnly") && forwarded.contains("SameSite=Strict"), forwarded);
        assertTrue(direct.contains("Secure"), direct);
    }

    @Test
    void repeatedFailuresLockTheSourceOutWithA429EvenForTheRightPassword() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertEquals(401, login("10.1.0.7", "wrong password " + i).getResponse().getStatus());
        }

        MvcResult locked = login("10.1.0.7", PASSWORD);

        assertEquals(429, locked.getResponse().getStatus());
        assertNotNull(locked.getResponse().getHeader("Retry-After"));
        assertTrue(locked.getResponse().getContentAsString().contains("\"error\""));
        assertEquals(200, login("10.1.0.8", PASSWORD).getResponse().getStatus());
    }

    @Test
    void aPasswordCannotBeSetWhileClientPasswordIsConfigured() throws Exception {
        mockMvc.perform(post("/api/auth/set-password").contentType(MediaType.APPLICATION_JSON).content(loginBody("another password")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void loggingOutEndsTheSession() throws Exception {
        Cookie cookie = signIn("10.1.0.9");
        mockMvc.perform(get("/api/client/status").cookie(cookie)).andExpect(status().isOk());

        MvcResult logout = mockMvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent()).andReturn();

        assertTrue(logout.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
        mockMvc.perform(get("/api/client/status").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void crossSiteWritesAreRefusedEvenWithAValidSession() throws Exception {
        Cookie cookie = signIn("10.1.0.10");

        mockMvc.perform(put("/api/client/pickup-interval").cookie(cookie).header("Origin", "http://evil.example.com").header("Host", "client.local:8081")
                .contentType(MediaType.APPLICATION_JSON).content("{\"interval\":\"PT1H\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/client/pairing").cookie(cookie).header("Sec-Fetch-Site", "cross-site")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/login").header("Origin", "http://evil.example.com").header("Host", "client.local:8081")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody(PASSWORD)))
            .andExpect(status().isForbidden());
    }

    @Test
    void aWriteWithNeitherBrowserHeadersNorTheCustomHeaderIsRefused() throws Exception {
        Cookie cookie = signIn("10.1.0.12");

        mockMvc.perform(post("/api/client/pairing").cookie(cookie)
                .with(request -> {
                    request.removeHeader("X-MediaGarrd-Request");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void sameOriginWritesAreAccepted() throws Exception {
        Cookie cookie = signIn("10.1.0.11");

        mockMvc.perform(post("/api/client/pairing").cookie(cookie).header("Origin", "http://client.local:8081").header("Host", "client.local:8081")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void everyResponseForbidsFraming() throws Exception {
        for (String path : List.of("/", "/index.html", "/api/auth/status", "/api/client/status", "/no-such-page.html")) {
            mockMvc.perform(get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Frame-Options", "DENY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Security-Policy", SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Referrer-Policy", "no-referrer"));
        }
        for (String path : List.of("/api/auth/status", "/api/client/status")) {
            mockMvc.perform(get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        }
    }
}
