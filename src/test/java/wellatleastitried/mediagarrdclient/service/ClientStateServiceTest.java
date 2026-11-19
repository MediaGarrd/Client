package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;

class ClientStateServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path tempDir;

    private final MediaGarrdProperties configuration = new MediaGarrdProperties();

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
    void aCorruptStateFileIsMovedAsideInsteadOfBeingOverwritten() throws IOException {
        Path file = tempDir.resolve("state.json");
        Files.writeString(file, "{\"activeServer\": \"http://a:8080\", \"pairings\": {broken");

        ClientStateService state = new ClientStateService(configuration, MAPPER);
        assertNull(state.getActiveServer());
        assertEquals(Duration.ofHours(12), state.getPickupInterval());
        state.setActiveServer("http://b:8080");

        List<String> moved = fileNames().stream().filter(name -> name.startsWith("state.json.corrupt-")).toList();
        assertEquals(1, moved.size());
        assertEquals("{\"activeServer\": \"http://a:8080\", \"pairings\": {broken", Files.readString(tempDir.resolve(moved.getFirst())));
        assertEquals("http://b:8080", new ClientStateService(configuration, MAPPER).getActiveServer());
    }

    @Test
    void aStateFileThatHoldsNullIsMovedAsideToo() throws IOException {
        Files.writeString(tempDir.resolve("state.json"), "null");

        ClientStateService state = new ClientStateService(configuration, MAPPER);

        assertNull(state.getActiveServer());
        assertTrue(fileNames().stream().anyMatch(name -> name.startsWith("state.json.corrupt-")));
        assertFalse(Files.exists(tempDir.resolve("state.json")));
    }

    @Test
    void aFailedSaveLeavesTheStateInMemoryUnchanged() throws IOException {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.setActiveServer("http://a:8080");
        state.storePairing("http://a:8080", new StoredPairing("tok", "id1", "Laptop"));
        Path blocker = tempDir.resolve("blocked");
        Files.writeString(blocker, "not a directory");
        Path file = tempDir.resolve("blocked").resolve("state.json");
        configuration.setStateFile(file.toString());
        ClientStateService failing = new ClientStateService(configuration, MAPPER);

        assertThrows(IllegalStateException.class, () -> failing.setPickupInterval(Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> failing.setActiveServer("http://b:8080"));
        assertThrows(IllegalStateException.class, () -> failing.storePairing("http://b:8080", new StoredPairing("t2", "id2", "x")));
        assertThrows(IllegalStateException.class, () -> failing.updateKnownServers(List.of("http://c:8080")));

        assertEquals(Duration.ofHours(12), failing.getPickupInterval());
        assertNull(failing.getActiveServer());
        assertFalse(failing.hasPairing("http://b:8080"));
        assertEquals(List.of(), failing.getKnownServers());
    }

    @Test
    void aFailedSaveKeepsAStoredPairing() throws IOException {
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.storePairing("http://a:8080", new StoredPairing("tok", "id1", "Laptop"));
        Files.delete(tempDir.resolve("state.json"));
        Files.createDirectory(tempDir.resolve("state.json"));
        Files.writeString(tempDir.resolve("state.json").resolve("x"), "x");

        assertThrows(IllegalStateException.class, () -> state.forgetPairing("http://a:8080"));

        assertTrue(state.hasPairing("http://a:8080"));
    }

    @Test
    void knowsWhichServersWereAlreadySelected() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.setActiveServer("http://a:8080");

        assertTrue(state.isKnownServer("http://a:8080/"));
        assertFalse(state.isKnownServer("http://z:8080"));
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
        assertEquals(List.of("http://a:8080", "http://b:8080"), state.getKnownServers());
    }

    @Test
    void aScanMergesFoundServersIntoTheKnownOnesWithoutDroppingAny() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.updateKnownServers(List.of("http://a:8080", "https://garrd.example.com"));
        state.setActiveServer("http://a:8080");

        state.updateKnownServers(List.of("http://c:8080", "http://a:8080"));

        assertEquals("http://a:8080", state.getActiveServer());
        assertEquals(List.of("http://a:8080", "https://garrd.example.com", "http://c:8080"), state.getKnownServers());
        assertEquals(state.getKnownServers(), new ClientStateService(configuration, MAPPER).getKnownServers());
    }

    @Test
    void anEmptyScanKeepsTheKnownServers() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.updateKnownServers(List.of("http://a:8080", "http://b:8080"));

        state.updateKnownServers(List.of());

        assertEquals(List.of("http://a:8080", "http://b:8080"), state.getKnownServers());
        assertEquals("http://a:8080", state.getActiveServer());
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

        assertThrows(InvalidRequestException.class, () -> state.setPickupInterval(Duration.ZERO));
        assertThrows(InvalidRequestException.class, () -> state.setPickupInterval(null));
        assertEquals(Duration.ofHours(12), state.getPickupInterval());
    }

    @Test
    void rejectsPickupIntervalsAboveOneYear() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);

        state.setPickupInterval(Duration.ofDays(365));
        InvalidRequestException tooLong = assertThrows(InvalidRequestException.class, () -> state.setPickupInterval(Duration.ofDays(365).plusSeconds(1)));

        assertTrue(tooLong.getMessage().contains("365 days"), tooLong.getMessage());
        assertEquals(Duration.ofDays(365), state.getPickupInterval());
    }

    @Test
    void anOutOfRangeStoredOrConfiguredIntervalFallsBackToTheDefault() throws IOException {
        Files.writeString(tempDir.resolve("state.json"), "{\"activeServer\":\"http://a:8080\",\"pickupIntervalSeconds\":999999999,\"knownServers\":[\"http://a:8080\"]}");

        ClientStateService stored = new ClientStateService(configuration, MAPPER);
        assertEquals(Duration.ofHours(12), stored.getPickupInterval());
        assertEquals("http://a:8080", stored.getActiveServer());

        configuration.setPickupInterval(Duration.ofDays(4000));
        Files.delete(tempDir.resolve("state.json"));
        assertEquals(Duration.ofHours(12), new ClientStateService(configuration, MAPPER).getPickupInterval());
    }

    @Test
    void anUnreadableStateFileFailsStartupAndIsLeftInPlace() throws IOException {
        Path file = tempDir.resolve("state.json");
        String content = "{\"activeServer\":\"http://a:8080\",\"pickupIntervalSeconds\":60,\"knownServers\":[\"http://a:8080\"]}";
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file), "the tests run as a user that ignores file permissions");
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ClientStateService(configuration, MAPPER));

            assertTrue(failure.getMessage().contains(file.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("UID"), failure.getMessage());
            assertEquals(List.of("state.json"), fileNames());
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        assertEquals(content, Files.readString(file));
    }

    @Test
    void aStateFileThatFailsWithAnIoErrorFailsStartupInsteadOfBeingMovedAside() throws IOException {
        Path file = tempDir.resolve("state.json");
        Files.createDirectory(file);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ClientStateService(configuration, MAPPER));

        assertTrue(failure.getMessage().contains(file.toString()), failure.getMessage());
        assertTrue(Files.isDirectory(file));
        assertEquals(List.of("state.json"), fileNames());
    }

    @Test
    void requiresAnActiveServer() {
        assertThrows(InvalidRequestException.class, () -> new ClientStateService(configuration, MAPPER).requireActiveServer());
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

    @Test
    void keepsTokensPerNormalizedServerUrlAndReloadsThem() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.storePairing("HTTPS://Garrd.Example.com:443/", new StoredPairing("tok-1", "id-1", "Home"));
        state.storePairing("http://10.0.0.5:38471", new StoredPairing("tok-2", "id-2", "Home"));

        ClientStateService reloaded = new ClientStateService(configuration, MAPPER);

        assertEquals("tok-1", reloaded.tokenFor("https://garrd.example.com").orElseThrow());
        assertEquals("tok-1", reloaded.tokenFor("https://garrd.example.com/").orElseThrow());
        assertEquals("tok-2", reloaded.tokenFor("http://10.0.0.5:38471").orElseThrow());
        assertTrue(reloaded.tokenFor("http://10.0.0.5").isEmpty());
        assertTrue(reloaded.tokenFor("https://garrd.example.com:8443").isEmpty());
        assertEquals("id-1", reloaded.pairingFor("https://garrd.example.com").orElseThrow().id());
    }

    @Test
    void forgettingAPairingRemovesItFromDiskAndNotifiesListeners() {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        List<String> notified = new ArrayList<>();
        state.addPairingListener(notified::add);
        state.storePairing("http://a:8080", new StoredPairing("tok", "id", "Home"));

        assertTrue(state.forgetPairing("http://a:8080"));
        assertFalse(state.forgetPairing("http://a:8080"));

        assertEquals(List.of("http://a:8080", "http://a:8080"), notified);
        assertFalse(new ClientStateService(configuration, MAPPER).hasPairing("http://a:8080"));
        assertFalse(readState().contains("tok"));
    }

    @Test
    void writesTheStateFileReadableByTheOwnerOnly() throws IOException {
        ClientStateService state = new ClientStateService(configuration, MAPPER);
        state.storePairing("http://a:8080", new StoredPairing("tok", "id", "Home"));
        state.setPickupInterval(Duration.ofMinutes(5));

        Path file = tempDir.resolve("state.json");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        assertEquals(List.of("state.json"), fileNames());
    }

    @Test
    void neverPrintsATokenInTextualForms() {
        StoredPairing pairing = new StoredPairing("super-secret-token", "id-1", "Home");

        assertFalse(pairing.toString().contains("super-secret-token"));
        assertFalse(new ClientStateService.ClientState("http://a", 60, null, List.of(), java.util.Map.of("http://a", pairing)).toString().contains("super-secret-token"));
    }

    @Test
    void migratesStoredServerUrlsToTheirNormalizedForm() throws IOException {
        Files.writeString(tempDir.resolve("state.json"),
            "{\"activeServer\":\"HTTP://Server:80/\",\"pickupIntervalSeconds\":60,\"knownServers\":[\"http://server\",\"http://other:8080/\"],"
                + "\"pairings\":{\"HTTP://Server:80/\":{\"token\":\"tok\",\"id\":\"i\",\"name\":\"n\"}}}");

        ClientStateService state = new ClientStateService(configuration, MAPPER);

        assertEquals("http://server", state.getActiveServer());
        assertEquals(List.of("http://server", "http://other:8080"), state.getKnownServers());
        assertEquals("tok", state.tokenFor("http://server").orElseThrow());
    }

    @Test
    void readsAStateFileWrittenBeforePairingExisted() throws IOException {
        Files.writeString(tempDir.resolve("state.json"), "{\"activeServer\":\"http://a:8080\",\"pickupIntervalSeconds\":60,\"knownServers\":[\"http://a:8080\"]}");

        ClientStateService state = new ClientStateService(configuration, MAPPER);

        assertEquals("http://a:8080", state.getActiveServer());
        assertFalse(state.hasPairing("http://a:8080"));
    }

    private String readState() {
        try {
            return Files.readString(tempDir.resolve("state.json"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
