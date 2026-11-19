package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
import java.util.Optional;
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

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.api.ApiExceptionHandler;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.PairingCodeDto;
import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdclient.dto.WebhookTestDto;
import wellatleastitried.mediagarrdclient.golden.Fixtures;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;

class ServerApiServiceTest {

    private record Exchange(String method, String path, String body) {
    }

    private final List<Exchange> exchanges = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private volatile Optional<String> token = Optional.of("secret-token");
    private final ServerApiService service = new ServerApiService(Duration.ofHours(6), url -> token, ServerTls.defaults());
    private HttpServer server;
    private String serverUrl;
    private int status = 200;
    private String response = "{}";
    private Duration delay = Duration.ZERO;
    private String location;
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
    void tellsListenersWhichServerRejectedTheToken() {
        List<String> rejected = new ArrayList<>();
        service.addTokenRejectedListener(rejected::add);
        status = 401;

        assertThrows(PairingRequiredException.class, () -> service.listBackups(serverUrl));

        assertEquals(List.of(serverUrl), rejected);
    }

    @Test
    void doesNotTellListenersWhenNoTokenIsStored() {
        List<String> rejected = new ArrayList<>();
        service.addTokenRejectedListener(rejected::add);
        token = Optional.empty();

        assertThrows(PairingRequiredException.class, () -> service.listBackups(serverUrl));

        assertEquals(List.of(), rejected);
    }

    @Test
    void savesADownloadOwnerOnly(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        response = "PK\u0003\u0004zip";
        java.nio.file.Path destination = tempDir.resolve("fresh").resolve("b1.zip");

        service.downloadById(serverUrl, "b1", destination, null);

        if (java.nio.file.Files.getFileStore(destination).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(destination)));
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(destination.getParent())));
        }
    }

    @Test
    void aStalledDownloadFailsAndLeavesNoPartFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        server.createContext("/api/v1/backups/stall/download", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            OutputStream out = exchange.getResponseBody();
            out.write("PK\u0003\u0004".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        ServerApiService stalling = new ServerApiService(Duration.ofHours(6), Duration.ofMillis(300), url -> token, ServerTls.defaults());
        java.nio.file.Path destination = tempDir.resolve("stall.zip");

        DownloadFailedException e = assertThrows(DownloadFailedException.class, () -> stalling.downloadById(serverUrl, "stall", destination, null));

        assertTrue(e.getMessage().contains("stopped sending data"));
        try (var files = java.nio.file.Files.list(tempDir)) {
            assertEquals(List.of(), files.toList());
        }
        stalling.shutdown();
    }

    @Test
    void aFailedDownloadMessageDoesNotLeakThePath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        response = "not a zip";

        DownloadFailedException e = assertThrows(DownloadFailedException.class, () -> service.downloadById(serverUrl, "b1", tempDir.resolve("b1.zip"), null));

        assertFalse(e.getMessage().contains(tempDir.toString()));
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
    void readsTheWebhookStatusAndTestFromTheServerPayloads() {
        response = Fixtures.read("server/webhook-status.json");
        assertEquals(new WebhookStatusDto(true, "https://discord.com"), service.webhook(serverUrl));
        response = Fixtures.read("server/webhook-test.json");
        assertEquals(new WebhookTestDto(false, 404, "Webhook answered 404"), service.testWebhook(serverUrl));

        assertEquals(new Exchange("GET", "/api/v1/notifications/webhook", ""), exchanges.get(0));
        assertEquals(new Exchange("POST", "/api/v1/notifications/webhook/test", ""), exchanges.get(1));
        assertEquals(List.of("Bearer secret-token", "Bearer secret-token"), authorizations);
    }

    @Test
    void sendsTheWebhookUrlAndClearsItWithAnEmptyString() {
        response = Fixtures.read("server/webhook-status.json");

        service.updateWebhook(serverUrl, "https://discord.com/api/webhooks/1/abc");
        service.updateWebhook(serverUrl, "");

        assertEquals(new Exchange("PUT", "/api/v1/notifications/webhook", "{\"url\":\"https://discord.com/api/webhooks/1/abc\"}"), exchanges.get(0));
        assertEquals(new Exchange("PUT", "/api/v1/notifications/webhook", "{\"url\":\"\"}"), exchanges.get(1));
    }

    @Test
    void theWebhookUrlIsNotWrittenToTheLog() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            response = Fixtures.read("server/webhook-status.json");
            service.updateWebhook(serverUrl, "https://discord.com/api/webhooks/1/SECRETTOKEN");
            service.testWebhook(serverUrl);
        } finally {
            root.detachAppender(logs);
        }

        assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains("SECRETTOKEN")));
    }

    @Test
    void aServerRejectionOfTheWebhookKeepsItsStatusAndMessage() {
        status = 429;
        response = "{\"error\":\"Wait 7 seconds before sending another test\"}";

        RestClientResponseException e = assertThrows(RestClientResponseException.class, () -> service.testWebhook(serverUrl));

        assertEquals(429, e.getStatusCode().value());
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
    void aFailedBackupRunCarriesTheServersReasonToTheDashboard() {
        status = 500;
        response = "{\"error\": \"Backup of radarr failed: disk is full\"}";

        BackupRunFailedException e = assertThrows(BackupRunFailedException.class, () -> service.triggerBackup(serverUrl));
        ResponseEntity<?> forwarded = new ApiExceptionHandler().backupRunFailed(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, forwarded.getStatusCode());
        assertEquals(Map.of("error", "Backup of radarr failed: disk is full"), forwarded.getBody());
    }

    @Test
    void aFailedBackupRunWithoutAReasonFallsBackToTheStatus() {
        status = 500;
        response = "<html>oops</html>";

        BackupRunFailedException e = assertThrows(BackupRunFailedException.class, () -> service.triggerBackup(serverUrl));

        assertEquals("MediaGarrd server responded with 500", e.getMessage());
    }

    @Test
    void everyOtherServerFailureKeepsTheGenericText() {
        status = 500;
        response = "{\"error\": \"Disk /srv/backups is full\"}";

        RestClientResponseException e = assertThrows(RestClientResponseException.class, () -> service.listBackups(serverUrl));

        assertEquals(Map.of("error", "MediaGarrd server responded with 500"), new ApiExceptionHandler().serverRejected(e).getBody());
    }

    @Test
    void reportsABackupRunThatOutlastsTheTimeout() {
        ServerApiService shortTimeout = new ServerApiService(Duration.ofMillis(200), url -> token, ServerTls.defaults());
        delay = Duration.ofSeconds(2);

        BackupRunTimeoutException e = assertThrows(BackupRunTimeoutException.class, () -> shortTimeout.triggerBackup(serverUrl));
        ResponseEntity<?> forwarded = new ApiExceptionHandler().backupRunTimedOut(e);

        assertEquals("POST", exchanges.get(0).method());
        assertEquals("/api/v1/backups/run", exchanges.get(0).path());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, forwarded.getStatusCode());
        assertTrue(e.getMessage().contains("still running on the server"));
    }

    @Test
    void rejectsABackupRunTimeoutBelowTheMinimum() {
        assertRejected(null);
        assertRejected(Duration.ofSeconds(-1));
        assertRejected(Duration.ZERO);
        IllegalStateException e = assertRejected(Duration.ofSeconds(59));
        assertTrue(e.getMessage().contains("at least PT1M"));
        MediaGarrdProperties ok = new MediaGarrdProperties();
        ok.setBackupRunTimeout(Duration.ofMinutes(1));
        new ServerApiService(ok, mock(ClientStateService.class), ServerTls.defaults());
    }

    private static IllegalStateException assertRejected(Duration timeout) {
        MediaGarrdProperties properties = new MediaGarrdProperties();
        properties.setBackupRunTimeout(timeout);
        return assertThrows(IllegalStateException.class, () -> new ServerApiService(properties, mock(ClientStateService.class), ServerTls.defaults()));
    }

    @Test
    void mapsAReadTimeoutOnTheOrdinaryClientsToGatewayTimeout() {
        ServerApiService impatient = new ServerApiService(Duration.ofHours(6), Duration.ofSeconds(60), Duration.ofMillis(200), Duration.ofMillis(200), url -> token, ServerTls.defaults());
        delay = Duration.ofSeconds(3);
        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> impatient.health(serverUrl));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, new ApiExceptionHandler().serverUnreachable(e).getStatusCode());
        assertEquals(Map.of("error", "MediaGarrd server did not respond in time"), new ApiExceptionHandler().serverUnreachable(e).getBody());
    }

    @Test
    void sendsTheBearerTokenOnEveryAuthenticatedCall() {
        ServiceDto radarr = new ServiceDto("radarr", List.of());

        response = "[]";
        service.listBackups(serverUrl);
        service.listServices(serverUrl);
        response = "{\"name\": \"radarr\", \"paths\": []}";
        service.triggerBackup(serverUrl);
        service.serverConfig(serverUrl);
        service.updateServerConfig(serverUrl, "/b", 3);
        service.updateServerSchedule(serverUrl, Duration.ofHours(1));
        service.addService(serverUrl, radarr);
        service.updateService(serverUrl, "radarr", radarr);
        status = 204;
        service.removeService(serverUrl, "radarr");
        service.revokeClient(serverUrl, "abc");

        assertEquals(10, authorizations.size());
        assertTrue(authorizations.stream().allMatch("Bearer secret-token"::equals), authorizations.toString());
    }

    @Test
    void sendsTheTokenOnHealthOnlyWhenPairedAndNeverOnTheVersion() {
        response = "{\"healthy\": true, \"running\": false, \"backupRetentionCount\": 4, \"backupInterval\": \"PT1H\"}";

        service.health(serverUrl);
        token = Optional.empty();
        service.health(serverUrl);
        token = Optional.of("secret-token");
        service.serverVersion(serverUrl);

        assertEquals(List.of("Bearer secret-token", "null", "null"), authorizations);
    }

    @Test
    void doesNotCallTheServerOnAnAuthenticatedRequestWithoutAToken() {
        token = Optional.empty();

        assertThrows(PairingRequiredException.class, () -> service.listBackups(serverUrl));
        assertThrows(PairingRequiredException.class, () -> service.downloadById(serverUrl, "b1", java.nio.file.Path.of("/nonexistent/x.zip"), null));

        assertTrue(exchanges.isEmpty());
    }

    @Test
    void turnsAServer401IntoPairingRequiredForEveryKindOfCall(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        status = 401;
        response = "{\"error\": \"Invalid or revoked token\"}";

        assertThrows(PairingRequiredException.class, () -> service.listBackups(serverUrl));
        assertThrows(PairingRequiredException.class, () -> service.health(serverUrl));
        assertThrows(PairingRequiredException.class, () -> service.triggerBackup(serverUrl));
        assertThrows(PairingRequiredException.class, () -> service.downloadById(serverUrl, "b1", dir.resolve("b.zip"), null));
        PairingRequiredException e = assertThrows(PairingRequiredException.class, () -> service.createPairingCode(serverUrl));
        ResponseEntity<?> forwarded = new ApiExceptionHandler().pairingRequired(e);

        assertEquals(HttpStatus.BAD_GATEWAY, forwarded.getStatusCode());
        assertEquals("required", ((Map<?, ?>) forwarded.getBody()).get("pairing"));
    }

    @Test
    void doesNotFollowARedirectAndNamesWhereItPointed() {
        status = 301;
        location = "https://mediagarrd.example.com/api/v1/backups";

        ServerRedirectException e = assertThrows(ServerRedirectException.class, () -> service.listBackups(serverUrl));

        assertEquals("https://mediagarrd.example.com/api/v1/backups", e.location());
        assertTrue(e.getMessage().contains("https://mediagarrd.example.com/api/v1/backups"));
        assertEquals(1, exchanges.size());
        assertEquals(HttpStatus.BAD_GATEWAY, new ApiExceptionHandler().serverRedirected(e).getStatusCode());
    }

    @Test
    void doesNotFollowARedirectOnADownloadEither(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        status = 302;
        location = "https://elsewhere.example.com/download";

        assertThrows(ServerRedirectException.class, () -> service.downloadById(serverUrl, "b1", dir.resolve("b.zip"), null));

        assertEquals(1, exchanges.size());
        assertEquals(List.of("Bearer secret-token"), authorizations);
    }

    @Test
    void pairsWithoutATokenAndKeepsTheTokenOutOfTheLog() {
        token = Optional.empty();
        response = "{\"token\": \"abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG\", \"id\": \"0123456789abcdef\", \"name\": \"Home\"}";

        StoredPairing pairing = service.pair(serverUrl, "123456", "Home");

        assertEquals("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", pairing.token());
        assertEquals("0123456789abcdef", pairing.id());
        assertFalse(pairing.toString().contains("abcdefghij"));
        assertEquals(new Exchange("POST", "/api/v1/pair", "{\"pin\":\"123456\",\"name\":\"Home\"}"), exchanges.getFirst());
        assertEquals("null", authorizations.getFirst());
    }

    @Test
    void aWrongPinIsABadRequestNotAPairingRequiredSignal() {
        token = Optional.empty();
        status = 401;
        response = "{\"error\": \"Invalid PIN\"}";

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> service.pair(serverUrl, "000000", "Home"));

        assertEquals("Invalid PIN", e.getMessage());
    }

    @Test
    void passesOnWhyPairingIsClosedOrLocked() {
        token = Optional.empty();
        status = 429;
        response = "{\"error\": \"Too many attempts\"}";

        RestClientResponseException e = assertThrows(RestClientResponseException.class, () -> service.pair(serverUrl, "000000", "Home"));

        assertEquals(Map.of("error", "Too many attempts"), new ApiExceptionHandler().serverRejected(e).getBody());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, new ApiExceptionHandler().serverRejected(e).getStatusCode());
    }

    @Test
    void confirmsAPairingWithTheNewTokenAndNotTheStoredOne() {
        token = Optional.of("stored-token");
        response = "{\"healthy\": true, \"running\": false, \"backupRetentionCount\": 4, \"backupInterval\": \"PT1H\"}";

        service.confirmPairing(serverUrl, "new-token");

        assertEquals(new Exchange("GET", "/api/v1/health", ""), exchanges.getFirst());
        assertEquals(List.of("Bearer new-token"), authorizations);
    }

    @Test
    void aPublicOnlyHealthReplyIsNotAConfirmation() {
        response = "{\"healthy\": true, \"backupInterval\": \"PT1H\"}";

        ConfirmationRefusedException e = assertThrows(ConfirmationRefusedException.class, () -> service.confirmPairing(serverUrl, "new-token"));

        assertTrue(e.getMessage().contains("did not confirm"), e.getMessage());
    }

    @Test
    void aRefusedConfirmationIsABadRequestAndDoesNotTellListenersTheStoredTokenFailed() {
        List<String> rejected = new ArrayList<>();
        service.addTokenRejectedListener(rejected::add);
        status = 401;
        response = "{\"error\": \"nope\"}";

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> service.confirmPairing(serverUrl, "new-token"));

        assertTrue(e.getMessage().contains("did not accept this pairing"), e.getMessage());
        assertTrue(rejected.isEmpty());
    }

    @Test
    void aServerErrorOnConfirmationIsAnUpstreamFailure() {
        status = 500;
        response = "{\"error\": \"Internal error\"}";

        UpstreamResponseException e = assertThrows(UpstreamResponseException.class, () -> service.confirmPairing(serverUrl, "new-token"));

        assertTrue(e.getMessage().contains("500"), e.getMessage());
        assertEquals(1, exchanges.size());
    }

    @Test
    void gatewayStatusesOnConfirmationAreMarkedRetryable() {
        for (int gateway : new int[] {502, 503, 504}) {
            exchanges.clear();
            status = gateway;
            response = "{}";

            ConfirmationUnavailableException e = assertThrows(ConfirmationUnavailableException.class, () -> service.confirmPairing(serverUrl, "new-token"));

            assertTrue(e.getMessage().contains(String.valueOf(gateway)), e.getMessage());
        }
    }

    @Test
    void aRedirectOnConfirmationIsRefused() {
        status = 302;
        location = "http://127.0.0.1:1/api/v1/health";

        assertThrows(ServerRedirectException.class, () -> service.confirmPairing(serverUrl, "new-token"));

        assertEquals(1, exchanges.size());
    }

    @Test
    void readsAPairingCode() {
        response = "{\"code\": \"ABCDE-FGHJK\", \"expiresAt\": \"2026-11-19T12:10:00Z\"}";

        PairingCodeDto code = service.createPairingCode(serverUrl);

        assertEquals("ABCDE-FGHJK", code.code());
        assertEquals(Instant.parse("2026-11-19T12:10:00Z"), code.expiresAt());
        assertEquals(new Exchange("POST", "/api/v1/pairing-codes", ""), exchanges.getFirst());
        assertEquals(1, exchanges.size());
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
        RuntimeException backupRun = assertThrows(RuntimeException.class, () -> service.triggerBackup("http://127.0.0.1:1"));
        assertInstanceOf(ResourceAccessException.class, backupRun);
        assertFalse(backupRun instanceof BackupRunTimeoutException);
    }

    @Test
    void treatsAConnectTimeoutAsATimeoutButNotAsAReadTimeout() {
        Throwable connect = new ResourceAccessException("x", new IOException("y", new HttpConnectTimeoutException("z")));

        assertTrue(RequestTimeouts.isTimeout(connect));
        assertFalse(RequestTimeouts.isReadTimeout(connect));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, new ApiExceptionHandler().serverUnreachable((ResourceAccessException) connect).getStatusCode());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchanges.add(new Exchange(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body));
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        try {
            release.await(delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
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
