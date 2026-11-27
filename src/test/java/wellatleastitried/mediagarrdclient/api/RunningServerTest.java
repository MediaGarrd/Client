package wellatleastitried.mediagarrdclient.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(60)
class RunningServerTest {

    @TempDir
    static Path dataDir;

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mediagarrd.client.state-file", () -> dataDir.resolve("state.json").toString());
        registry.add("mediagarrd.client.download-directory", () -> dataDir.resolve("downloads").toString());
        registry.add("mediagarrd.client.preferred-server-ip", () -> "");
        registry.add("mediagarrd.client.github-api-base-url", () -> "http://127.0.0.1:1");
        registry.add("mediagarrd.client.password", () -> "");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path)).header("X-MediaGarrd-Request", "1").timeout(Duration.ofSeconds(20));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String signIn() throws Exception {
        String body = "{\"password\":\"a long enough password\"}";
        HttpResponse<String> response = send(request("/api/auth/set-password").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (response.statusCode() == 409) {
            response = send(request("/api/auth/login").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }
        assertEquals(200, response.statusCode());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return cookie.substring(0, cookie.indexOf(';'));
    }

    @Test
    void servesSecurityHeadersOnPagesAndApiResponses() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/js/main.js", "/api/auth/status", "/api/client/status", "/nothing-here"}) {
            HttpResponse<String> response = send(request(path).GET().build());

            assertEquals(SecurityHeadersFilter.CONTENT_SECURITY_POLICY, response.headers().firstValue("Content-Security-Policy").orElse(null), path);
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null), path);
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null), path);
            assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null), path);
            if (path.startsWith("/api/")) {
                assertEquals(java.util.List.of("no-store"), response.headers().allValues("Cache-Control"), path);
            }
        }
    }

    @Test
    void anUnknownApiPathAnswersWithTheErrorShape() throws Exception {
        String cookie = signIn();

        HttpResponse<String> response = send(request("/api/client/no-such-endpoint").header("Cookie", cookie).GET().build());

        assertTrue(response.statusCode() >= 400 && response.statusCode() < 500);
        assertTrue(response.body().contains("\"error\""));
    }

    @Test
    void refusesARequestBodyOverTheCapWhetherItIsDeclaredOrChunked() throws Exception {
        byte[] oversized = ("{\"password\":\"" + "x".repeat((int) BodyLimitFilter.MAX_BODY_BYTES + 10) + "\"}").getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> declared = send(request("/api/auth/login").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(oversized)).build());
        HttpResponse<String> chunked = send(request("/api/auth/login").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized))).build());

        assertEquals(413, declared.statusCode());
        assertEquals("{\"error\":\"Request body is too large\"}", declared.body());
        assertEquals(413, chunked.statusCode());
        assertEquals("{\"error\":\"Request body is too large\"}", chunked.body());
    }

    @Test
    void aChunkedBodyWithinTheCapStillWorks() throws Exception {
        signIn();
        byte[] small = "{\"password\":\"wrong password\"}".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = send(request("/api/auth/login").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(small))).build());

        assertEquals(401, response.statusCode());
        assertEquals("{\"error\":\"Incorrect password\"}", response.body());
    }

    @Test
    void theEventStreamNeedsASessionDeliversASnapshotAndEndsWhenTheSessionDoes() throws Exception {
        assertEquals(401, send(request("/api/client/events").header("Accept", "text/event-stream").GET().build()).statusCode());
        String cookie = signIn();

        HttpResponse<InputStream> stream = client.send(request("/api/client/events").header("Cookie", cookie).header("Accept", "text/event-stream").GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, stream.statusCode());
        assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertEquals(java.util.List.of("no-store"), stream.headers().allValues("Cache-Control"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8));
        String line;
        String event = null;
        while ((line = reader.readLine()) != null && event == null) {
            if (line.startsWith("event:")) {
                event = line;
            }
        }
        assertEquals("event:snapshot", event);

        CompletableFuture<Boolean> ended = CompletableFuture.supplyAsync(() -> {
            try {
                while (reader.readLine() != null) {
                    continue;
                }
                return true;
            } catch (java.io.IOException e) {
                return true;
            }
        });
        HttpResponse<String> logout = send(request("/api/auth/logout").header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(204, logout.statusCode());

        assertTrue(ended.get(10, TimeUnit.SECONDS));
        assertNotNull(logout.headers().firstValue("Set-Cookie").orElse(null));
    }
}
