package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import wellatleastitried.mediagarrdclient.golden.Fixtures;

@SpringBootTest
class StateFilesGoldenTest {

    @TempDir
    static Path dataDir;

    @Autowired
    ClientStateService state;

    @Autowired
    PasswordService passwords;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Files.writeString(dataDir.resolve("state.json"), Fixtures.read("disk/state.json"), StandardCharsets.UTF_8);
        Files.writeString(dataDir.resolve("password.json"), Fixtures.read("disk/password.json"), StandardCharsets.UTF_8);
        registry.add("mediagarrd.client.state-file", () -> dataDir.resolve("state.json").toString());
        registry.add("mediagarrd.client.download-directory", () -> dataDir.resolve("downloads").toString());
        registry.add("mediagarrd.client.preferred-server-ip", () -> "");
        registry.add("mediagarrd.client.github-api-base-url", () -> "http://127.0.0.1:1");
        registry.add("mediagarrd.client.password", () -> "");
    }

    @Test
    void stateWrittenInTodaysFormatStillLoadsWithItsPairings() {
        assertEquals("http://192.168.1.20:38471", state.getActiveServer());
        assertEquals(Duration.ofSeconds(5400), state.getPickupInterval());
        assertEquals(Instant.parse("2026-11-20T03:15:00Z"), state.getLastPickupAt());
        assertEquals(List.of("http://192.168.1.20:38471", "https://backup.example.net:38471"), state.getKnownServers());
        assertEquals(Optional.of("golden-token-one"), state.tokenFor("http://192.168.1.20:38471"));
        assertEquals(Optional.of(new StoredPairing("golden-token-one", "c0ffee00-0000-4000-8000-000000000001", "Golden Laptop")),
            state.pairingFor("http://192.168.1.20:38471"));
        assertEquals(Optional.of(new StoredPairing("golden-token-two", "c0ffee00-0000-4000-8000-000000000002", null)),
            state.pairingFor("https://backup.example.net:38471"));
    }

    @Test
    void savingWritesTheSameShapeAsToday() throws Exception {
        state.setPickupInterval(Duration.ofSeconds(5400));

        String written = Files.readString(dataDir.resolve("state.json"));
        JSONAssert.assertEquals(Fixtures.read("disk/state.json"), written, JSONCompareMode.STRICT);
        assertEquals(keys(Fixtures.read("disk/state.json")), keys(written));
    }

    private static List<String> keys(String json) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    @Test
    void aStoredPasswordStillLoadsAndVerifies() {
        assertTrue(passwords.isConfigured());
        assertFalse(passwords.isFromEnvironment());
        assertTrue(passwords.matches("golden password"));
        assertFalse(passwords.matches("another password"));
    }
}
