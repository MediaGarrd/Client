package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.PairingCodeDto;
import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.golden.Fixtures;

class ServerPayloadGoldenTest {

    private final ServerApiService service = new ServerApiService(Duration.ofHours(6), url -> Optional.of("secret-token"), ServerTls.defaults());
    private HttpServer server;
    private String serverUrl;
    private volatile int status = 200;
    private volatile String response = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsThePublicHealthPayloadWithFieldsTheServerDoesNotSend() {
        response = Fixtures.read("server/health-public.json");

        ServerStatusDto health = service.health(serverUrl);

        assertEquals(new ServerStatusDto(true, false, 0, Duration.ofHours(6)), health);
    }

    @Test
    void readsTheAuthenticatedHealthPayload() {
        response = Fixtures.read("server/health-status.json");

        ServerStatusDto health = service.health(serverUrl);

        assertEquals(new ServerStatusDto(true, false, 7, Duration.ofHours(12)), health);
    }

    @Test
    void readsTheVersionPayload() {
        response = Fixtures.read("server/version.json");

        VersionStatusDto version = service.serverVersion(serverUrl);

        assertEquals(new VersionStatusDto("0.1.0", "0.2.0", "https://github.com/MediaGarrd/Server/releases/tag/v0.2.0", true, CheckState.OK,
            Instant.parse("2026-11-20T10:15:30.123456Z"), null), version);
    }

    @Test
    void readsTheBackupList() {
        response = Fixtures.read("server/backups.json");

        List<BackupDto> backups = service.listBackups(serverUrl);

        assertEquals(List.of(
            new BackupDto("backup-2026-11-20", "mediagarrd-2026-11-20.zip", 1048576L, Instant.parse("2026-11-20T10:15:30.123456Z")),
            new BackupDto("backup-2026-11-19", "mediagarrd-2026-11-19.zip", 2097152L, Instant.parse("2026-11-19T04:00:00Z"))), backups);
    }

    @Test
    void readsTheConfigPayload() {
        response = Fixtures.read("server/config.json");

        ServerConfigDto config = service.serverConfig(serverUrl);

        assertEquals(new ServerConfigDto("/var/backups/mediagarrd", 7, Duration.ofHours(6)), config);
    }

    @Test
    void readsTheServiceList() {
        response = Fixtures.read("server/services.json");

        List<ServiceDto> services = service.listServices(serverUrl);

        assertEquals(List.of(
            new ServiceDto("jellyfin", List.of("/srv/jellyfin/config", "/srv/jellyfin/data")),
            new ServiceDto("radarr", List.of("/srv/radarr"))), services);
    }

    @Test
    void readsThePairResponse() {
        response = Fixtures.read("server/pair-response.json");

        StoredPairing pairing = service.pair(serverUrl, "123456", "Golden Laptop");

        assertEquals("golden-token", pairing.token());
        assertEquals("c0ffee00-0000-4000-8000-000000000003", pairing.id());
        assertEquals("Golden Laptop", pairing.name());
    }

    @Test
    void readsThePairingCode() {
        response = Fixtures.read("server/pairing-code.json");

        PairingCodeDto code = service.createPairingCode(serverUrl);

        assertEquals(new PairingCodeDto("482913", Instant.parse("2026-11-20T10:25:30.500Z")), code);
    }

    @Test
    void readsTheErrorMessageFromTheServerErrorShape() {
        status = 401;
        response = Fixtures.read("server/error-4xx.json");

        var e = assertThrows(InvalidRequestException.class, () -> service.pair(serverUrl, "000000", null));

        assertEquals("The PIN is not valid", e.getMessage());
    }

    @Test
    void readsTheErrorMessageFromSpringsDefaultErrorBody() {
        status = 401;
        response = Fixtures.read("server/error-spring-default.json");

        var e = assertThrows(InvalidRequestException.class, () -> service.pair(serverUrl, "000000", null));

        assertEquals("Unauthorized", e.getMessage());
    }

    @Test
    void exposesTheServerErrorBodyOfAnUnexpectedStatus() {
        status = 409;
        response = Fixtures.read("server/error-4xx.json");

        RestClientResponseException e = assertThrows(RestClientResponseException.class, () -> service.createPairingCode(serverUrl));

        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getResponseBodyAsString().contains("The PIN is not valid"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
