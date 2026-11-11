package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import wellatleastitried.mediagarrdclient.Configuration;

class ClientStateServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final Configuration configuration = new Configuration();

    @BeforeEach
    void setUp() {
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
    }

    @Test
    void persistsAndLoadsState() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.updateKnownServers(List.of("http://a:8080", "http://b:8080"));
        state.setActiveServer("http://b:8080");
        state.setPickupInterval(Duration.ofMinutes(30));

        ClientStateService reloaded = new ClientStateService(configuration, MAPPER);
        assertEquals("http://b:8080", reloaded.getActiveServer());
        assertEquals(Duration.ofMinutes(30), reloaded.getPickupInterval());
        assertEquals(List.of("http://a:8080", "http://b:8080"), reloaded.getKnownServers());
        assertEquals(List.of("state.json"), fileNames());
    }

    @Test
    void keepsTheActiveServerWhenARescanDoesNotFindIt() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.setActiveServer("http://a:8080");

        state.updateKnownServers(List.of("http://b:8080"));

        assertEquals("http://a:8080", state.getActiveServer());
        assertEquals(List.of("http://b:8080", "http://a:8080"), state.getKnownServers());
    }

    @Test
    void selectsTheFirstDiscoveredServerWhenNoneIsActive() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);

        state.updateKnownServers(List.of("http://b:8080", "http://b:8080", "http://c:8080"));

        assertEquals("http://b:8080", state.getActiveServer());
        assertEquals(List.of("http://b:8080", "http://c:8080"), state.getKnownServers());
    }

    @Test
    void rejectsNonPositivePickupIntervals() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);

        assertThrows(IllegalArgumentException.class, () -> state.setPickupInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> state.setPickupInterval(null));
        assertEquals(Duration.ofHours(12), state.getPickupInterval());
    }

    @Test
    void requiresAnActiveServer() {
        assertThrows(ResponseStatusException.class, () -> new ClientStateService(configuration, MAPPER).requireActiveServer());
    }

    @Test
    void startsWithDefaultsWhenTheStateFileIsUnreadable() throws IOException {
        Files.writeString(tempDir.resolve("state.json"), "{not json");

        ClientStateService state = new ClientStateService(configuration, MAPPER);

        assertNull(state.getActiveServer());
        assertEquals(Duration.ofHours(12), state.getPickupInterval());
    }

    private List<String> fileNames() {
        try (var files = Files.list(tempDir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
