package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import wellatleastitried.mediagarrdclient.golden.Fixtures;

class StateFileMissingFieldsGoldenTest {

    static void register(DynamicPropertyRegistry registry, Path dataDir, String fixture) throws IOException {
        Files.writeString(dataDir.resolve("state.json"), Fixtures.read(fixture), StandardCharsets.UTF_8);
        registry.add("mediagarrd.client.state-file", () -> dataDir.resolve("state.json").toString());
        registry.add("mediagarrd.client.download-directory", () -> dataDir.resolve("downloads").toString());
        registry.add("mediagarrd.client.preferred-server-ip", () -> "");
        registry.add("mediagarrd.client.github-api-base-url", () -> "http://127.0.0.1:1");
        registry.add("mediagarrd.client.password", () -> "");
    }

    static void assertPairingLoaded(ClientStateService state) {
        assertEquals("http://192.168.1.20:38471", state.getActiveServer());
        assertEquals(Duration.ofHours(12), state.getPickupInterval());
        assertNull(state.getLastPickupAt());
        assertEquals(Optional.of(new StoredPairing("golden-token-one", "c0ffee00-0000-4000-8000-000000000001", "Golden Laptop")),
            state.pairingFor("http://192.168.1.20:38471"));
    }

    @SpringBootTest
    static class MissingInterval {

        @TempDir
        static Path dataDir;

        @Autowired
        ClientStateService state;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) throws IOException {
            register(registry, dataDir, "disk/state-no-interval.json");
        }

        @Test
        void stateWithoutPickupIntervalStillLoadsItsPairings() {
            assertPairingLoaded(state);
        }
    }

    @SpringBootTest
    static class NullInterval {

        @TempDir
        static Path dataDir;

        @Autowired
        ClientStateService state;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) throws IOException {
            register(registry, dataDir, "disk/state-null-interval.json");
        }

        @Test
        void stateWithNullPickupIntervalStillLoadsItsPairings() {
            assertPairingLoaded(state);
        }
    }
}
