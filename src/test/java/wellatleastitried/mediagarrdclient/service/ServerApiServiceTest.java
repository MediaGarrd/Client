package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.api.ApiExceptionHandler;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;

class ServerApiServiceTest {

    private record Exchange(String method, String path, String body) {
    }

    private final List<Exchange> exchanges = new ArrayList<>();
    private final ServerApiService service = new ServerApiService(Duration.ofHours(6));
    private HttpServer server;
    private String serverUrl;
    private int status = 200;
    private String response = "{}";
    private Duration delay = Duration.ZERO;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void sendsConfigUpdatesWithOmittedFieldsAsNull() {
        response = "{\"backupLocation\": \"/backups\", \"retentionCount\": 5, \"backupInterval\": \"PT6H\"}";

        ServerConfigDto config = service.updateServerConfig(serverUrl, null, 5);

        assertEquals(new ServerConfigDto("/backups", 5, Duration.ofHours(6)), config);
        assertEquals(new Exchange("PUT", "/api/v1/config", "{\"backupLocation\":null,\"retentionCount\":5}"), exchanges.getFirst());
    }

    @Test
    void readsTheServerVersionStatusAndIgnoresUnknownFields() {
        response = "{\"currentVersion\": \"0.1.0\", \"latestVersion\": \"0.2.0\", \"releaseUrl\": \"https://github.com/MediaGarrd/Server/releases/tag/v0.2.0\","
            + " \"updateAvailable\": true, \"checkState\": \"OK\", \"checkedAt\": \"2026-11-11T18:00:00Z\", \"message\": null, \"extra\": 1}";

        VersionStatusDto version = service.serverVersion(serverUrl);

        assertEquals(new VersionStatusDto("0.1.0", "0.2.0", "https://github.com/MediaGarrd/Server/releases/tag/v0.2.0", true, CheckState.OK, Instant.parse("2026-11-11T18:00:00Z"), null), version);
        assertEquals(new Exchange("GET", "/api/v1/version", ""), exchanges.getFirst());
    }

    @Test
    void sendsScheduleAsIsoDuration() {
        response = "{\"healthy\": true, \"running\": false, \"backupRetentionCount\": 4, \"backupInterval\": \"PT1H30M\"}";

        assertEquals(Duration.ofMinutes(90), service.updateServerSchedule(serverUrl, Duration.ofMinutes(90)).backupInterval());
        assertEquals(new Exchange("PUT", "/api/v1/schedule", "{\"backupInterval\":\"PT1H30M\"}"), exchanges.getFirst());
    }

    @Test
    void encodesServiceNamesInPaths() {
        ServiceDto radarr = new ServiceDto("radarr", List.of("/srv/radarr"));
        response = "{\"name\": \"radarr\", \"paths\": [\"/srv/radarr\"]}";

        assertEquals(radarr, service.updateService(serverUrl, "old name", radarr));
        status = 204;
        service.removeService(serverUrl, "radarr");

        assertEquals("/api/v1/services/old%20name", exchanges.get(0).path());
        assertEquals(new Exchange("DELETE", "/api/v1/services/radarr", ""), exchanges.get(1));
    }

    @Test
    void forwardsTheServerErrorMessageAndStatus() {
        status = 409;
        response = "{\"error\": \"Service already exists: radarr\"}";

        RestClientResponseException e = assertThrows(RestClientResponseException.class,
            () -> service.addService(serverUrl, new ServiceDto("radarr", List.of("/srv/radarr"))));
        ResponseEntity<?> forwarded = new ApiExceptionHandler().serverRejected(e);

        assertEquals(HttpStatus.CONFLICT, forwarded.getStatusCode());
        assertEquals(Map.of("error", "Service already exists: radarr"), forwarded.getBody());
    }

    @Test
    void fallsBackToTheStatusWhenTheServerSendsNoMessage() {
        status = 502;
        response = "<html>Bad Gateway</html>";

        RestClientResponseException e = assertThrows(RestClientResponseException.class, () -> service.serverConfig(serverUrl));

        assertEquals(Map.of("error", "MediaGarrd server responded with 502"), new ApiExceptionHandler().serverRejected(e).getBody());
    }

    @Test
    void reportsABackupRunThatOutlastsTheTimeout() {
        ServerApiService shortTimeout = new ServerApiService(Duration.ofMillis(200));
        delay = Duration.ofSeconds(2);

        BackupRunTimeoutException e = assertThrows(BackupRunTimeoutException.class, () -> shortTimeout.triggerBackup(serverUrl));
        ResponseEntity<?> forwarded = new ApiExceptionHandler().backupRunTimedOut(e);

        assertEquals("POST", exchanges.get(0).method());
        assertEquals("/api/v1/backups/run", exchanges.get(0).path());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, forwarded.getStatusCode());
        assertTrue(e.getMessage().contains("still running on the server"));
    }

    @Test
    void waitsForABackupRunWithinTheTimeout() {
        delay = Duration.ofMillis(300);

        service.triggerBackup(serverUrl);

        assertEquals(1, exchanges.size());
    }

    @Test
    void rejectsABackupRunTimeoutBelowTheMinimum() {
        assertRejected(null);
        assertRejected(Duration.ofSeconds(-1));
        assertRejected(Duration.ZERO);
        IllegalStateException e = assertRejected(Duration.ofSeconds(59));
        assertTrue(e.getMessage().contains("at least PT1M"));
        Configuration ok = new Configuration();
        ok.setBackupRunTimeout(Duration.ofMinutes(1));
        new ServerApiService(ok);
    }

    private static IllegalStateException assertRejected(Duration timeout) {
        Configuration properties = new Configuration();
        properties.setBackupRunTimeout(timeout);
        return assertThrows(IllegalStateException.class, () -> new ServerApiService(properties));
    }

    @Test
    void mapsAReadTimeoutOnTheOrdinaryClientsToGatewayTimeout() {
        delay = Duration.ofSeconds(8);
        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> service.health(serverUrl));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, new ApiExceptionHandler().serverUnreachable(e).getStatusCode());
        assertEquals(Map.of("error", "MediaGarrd server did not respond in time"), new ApiExceptionHandler().serverUnreachable(e).getBody());
    }

    @Test
    void survivesACyclicCauseChain() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);

        assertFalse(RequestTimeouts.isTimeout(a));
        assertFalse(RequestTimeouts.isReadTimeout(a));
    }

    @Test
    void mapsAConnectionRefusedToBadGateway() {
        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> service.health("http://127.0.0.1:1"));

        assertEquals(HttpStatus.BAD_GATEWAY, new ApiExceptionHandler().serverUnreachable(e).getStatusCode());
    }

    @Test
    void treatsAConnectTimeoutAsATimeoutButNotAsAReadTimeout() {
        Throwable connect = new ResourceAccessException("x", new IOException("y", new HttpConnectTimeoutException("z")));

        assertTrue(RequestTimeouts.isTimeout(connect));
        assertFalse(RequestTimeouts.isReadTimeout(connect));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, new ApiExceptionHandler().serverUnreachable((ResourceAccessException) connect).getStatusCode());
    }

    @Test
    void doesNotReportAnUnreachableServerAsATimeout() {
        assertThrows(ResourceAccessException.class, () -> service.triggerBackup("http://127.0.0.1:1"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchanges.add(new Exchange(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body));
        try {
            release.await(delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
