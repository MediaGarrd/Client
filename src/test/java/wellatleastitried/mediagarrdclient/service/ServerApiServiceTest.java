package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdclient.api.ApiExceptionHandler;
import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;

class ServerApiServiceTest {

    private record Exchange(String method, String path, String body) {
    }

    private final List<Exchange> exchanges = new ArrayList<>();
    private final ServerApiService service = new ServerApiService();
    private HttpServer server;
    private String serverUrl;
    private int status = 200;
    private String response = "{}";

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
    void sendsConfigUpdatesWithOmittedFieldsAsNull() {
        response = "{\"backupLocation\": \"/backups\", \"retentionCount\": 5, \"backupInterval\": \"PT6H\"}";

        ServerConfigDto config = service.updateServerConfig(serverUrl, null, 5);

        assertEquals(new ServerConfigDto("/backups", 5, Duration.ofHours(6)), config);
        assertEquals(new Exchange("PUT", "/api/v1/config", "{\"backupLocation\":null,\"retentionCount\":5}"), exchanges.getFirst());
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

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchanges.add(new Exchange(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body));
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
