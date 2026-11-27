package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;

class PasswordServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int FAST = 1000;

    @TempDir
    Path tempDir;

    private final MediaGarrdProperties configuration = new MediaGarrdProperties();

    @BeforeEach
    void setUp() {
        configuration.setStateFile(tempDir.resolve("state.json").toString());
    }

    private PasswordService service() {
        return new PasswordService(configuration, MAPPER, FAST);
    }

    @Test
    void startsWithoutAPasswordAndLetsTheFirstCallerSetOne() {
        PasswordService passwords = service();
        assertFalse(passwords.isConfigured());
        assertFalse(passwords.matches("anything"));

        passwords.setInitial("correct horse");

        assertTrue(passwords.isConfigured());
        assertTrue(passwords.matches("correct horse"));
        assertFalse(passwords.matches("correct horsf"));
        assertFalse(passwords.matches(null));
        assertTrue(service().matches("correct horse"));
    }

    @Test
    void refusesASecondInitialPassword() {
        PasswordService passwords = service();
        passwords.setInitial("correct horse");

        assertThrows(ConflictException.class, () -> passwords.setInitial("another one"));

        assertTrue(passwords.matches("correct horse"));
    }

    @Test
    void rejectsPasswordsThatAreTooShortOrTooLong() {
        PasswordService passwords = service();

        assertThrows(InvalidRequestException.class, () -> passwords.setInitial("short"));
        assertThrows(InvalidRequestException.class, () -> passwords.setInitial(null));
        assertThrows(InvalidRequestException.class, () -> passwords.setInitial("x".repeat(PasswordService.MAX_LENGTH + 1)));
        assertFalse(passwords.isConfigured());
        assertFalse(Files.exists(tempDir.resolve("password.json")));
    }

    @Test
    void theStoredFileHoldsOnlyAHashWithItsParameters() throws IOException {
        PasswordService first = service();
        first.setInitial("correct horse");
        Path file = tempDir.resolve("password.json");
        String content = Files.readString(file);
        JsonNode json = MAPPER.readTree(content);

        assertFalse(content.contains("correct horse"));
        assertEquals(FAST, json.get("iterations").asInt());
        assertEquals(16, java.util.Base64.getDecoder().decode(json.get("salt").asString()).length);
        assertEquals(32, java.util.Base64.getDecoder().decode(json.get("hash").asString()).length);
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void twoInstallsWithTheSamePasswordGetDifferentSalts() throws IOException {
        service().setInitial("correct horse");
        String first = Files.readString(tempDir.resolve("password.json"));
        Files.delete(tempDir.resolve("password.json"));
        service().setInitial("correct horse");

        assertFalse(first.equals(Files.readString(tempDir.resolve("password.json"))));
    }

    @Test
    void anEnvironmentPasswordOverridesAndDisablesSetting() {
        configuration.setPassword("from the environment");
        PasswordService passwords = service();

        assertTrue(passwords.isConfigured());
        assertTrue(passwords.isFromEnvironment());
        assertTrue(passwords.matches("from the environment"));
        assertFalse(passwords.matches("something else"));
        assertThrows(ConflictException.class, () -> passwords.setInitial("a new password"));
    }

    @Test
    void anEnvironmentPasswordWinsOverAStoredOne() {
        service().setInitial("stored password");
        configuration.setPassword("from the environment");
        PasswordService passwords = service();

        assertTrue(passwords.matches("from the environment"));
        assertFalse(passwords.matches("stored password"));
    }

    @Test
    void resetRemovesTheStoredPasswordSoAnotherCanBeSet() {
        service().setInitial("old password");
        configuration.setResetPassword(true);

        PasswordService passwords = service();

        assertFalse(passwords.isConfigured());
        assertFalse(Files.exists(tempDir.resolve("password.json")));
        passwords.setInitial("new password");
        assertTrue(passwords.matches("new password"));
    }

    @Test
    void refusesToStartOnACorruptPasswordFileInsteadOfOpeningTheFirstVisitWindow() throws IOException {
        Files.writeString(tempDir.resolve("password.json"), "{not json");

        IllegalStateException e = assertThrows(IllegalStateException.class, this::service);

        assertTrue(e.getMessage().contains("CLIENT_RESET_PASSWORD"));
    }

    @Test
    void usesTheDocumentedIterationCountByDefault() {
        assertEquals(600_000, PasswordService.ITERATIONS);
    }
}
