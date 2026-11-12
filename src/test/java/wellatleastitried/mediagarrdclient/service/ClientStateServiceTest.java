package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
    void setsTheActiveServerOnlyWhileNoneIsSelected() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        List<String> notified = new ArrayList<>();
        state.addActiveServerListener(notified::add);

        state.setActiveServerIfBlank("http://a:8080");
        state.setActiveServerIfBlank("http://b:8080");
        state.setActiveServerIfBlank(" ");

        assertEquals("http://a:8080", state.getActiveServer());
        assertEquals(List.of("http://a:8080"), state.getKnownServers());
        assertEquals(List.of("http://a:8080"), notified);
        assertEquals("http://a:8080", new ClientStateService(configuration, MAPPER).getActiveServer());
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

    @Test
    void notifiesListenersOnlyWhenTheActiveServerChanges() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        List<String> notified = new ArrayList<>();
        state.addActiveServerListener(notified::add);

        state.setActiveServer("http://a:8080");
        state.setActiveServer("http://a:8080");
        state.updateKnownServers(List.of("http://b:8080"));
        state.setActiveServer("http://b:8080");
        state.setActiveServer("");

        assertEquals(List.of("http://a:8080", "http://b:8080", ""), notified);
    }

    @Test
    void notifiesListenersWhenARescanPicksTheFirstActiveServer() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        List<String> notified = new ArrayList<>();
        state.addActiveServerListener(notified::add);

        state.updateKnownServers(List.of("http://a:8080", "http://b:8080"));
        state.updateKnownServers(List.of("http://b:8080"));

        assertEquals(List.of("http://a:8080"), notified);
    }

    @Test
    @Timeout(10)
    void notifiesListenersWithoutHoldingTheStateLock() throws InterruptedException {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        AtomicReference<String> seenByOtherThread = new AtomicReference<>();
        state.addActiveServerListener(url -> {
            Thread reader = new Thread(() -> seenByOtherThread.set(state.getActiveServer()));
            reader.start();
            try {
                reader.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        state.setActiveServer("http://a:8080");

        assertEquals("http://a:8080", seenByOtherThread.get());
    }

    @Test
    void keepsNotifyingWhenAListenerFails() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        List<String> notified = new ArrayList<>();
        state.addActiveServerListener(url -> {
            throw new IllegalStateException("boom");
        });
        state.addActiveServerListener(notified::add);

        state.setActiveServer("http://a:8080");

        assertTrue(notified.contains("http://a:8080"));
    }

    @Test
    void notifiesKnownServersListenersOnlyWhenTheListChanges() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        int[] calls = {0};
        state.addKnownServersListener(() -> calls[0]++);

        state.updateKnownServers(List.of("http://a:8080", "http://b:8080"));
        assertEquals(1, calls[0]);
        state.updateKnownServers(List.of("http://a:8080", "http://b:8080"));
        assertEquals(1, calls[0]);
        state.setActiveServer("http://a:8080");
        assertEquals(1, calls[0]);
        state.setActiveServer("http://c:8080");
        assertEquals(2, calls[0]);
        state.setActiveServer("http://c:8080");
        assertEquals(2, calls[0]);
        state.updateKnownServers(List.of("http://d:8080"));
        assertEquals(3, calls[0]);
    }

    @Test
    @Timeout(10)
    void notifiesKnownServersListenersWithoutHoldingTheStateLock() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        AtomicReference<List<String>> seenByOtherThread = new AtomicReference<>();
        state.addKnownServersListener(() -> {
            Thread reader = new Thread(() -> seenByOtherThread.set(state.getKnownServers()));
            reader.start();
            try {
                reader.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        state.updateKnownServers(List.of("http://a:8080"));

        assertEquals(List.of("http://a:8080"), seenByOtherThread.get());
    }
}
