package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;

@Service
public class PasswordService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordService.class);
    static final int ITERATIONS = 600_000;
    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 256;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    record StoredHash(int iterations, String salt, String hash) {
    }

    private final Path file;
    private final ObjectMapper mapper;
    private final int iterations;
    private final SecureRandom random = new SecureRandom();
    private final StoredHash environment;
    private StoredHash stored;

    @Autowired
    public PasswordService(MediaGarrdProperties configuration, ObjectMapper mapper) {
        this(configuration, mapper, ITERATIONS);
    }

    PasswordService(MediaGarrdProperties configuration, ObjectMapper mapper, int iterations) {
        this.file = Path.of(configuration.getStateFile()).toAbsolutePath().normalize().resolveSibling("password.json");
        this.mapper = mapper;
        this.iterations = iterations;
        String fromEnvironment = configuration.getPassword();
        this.environment = fromEnvironment == null || fromEnvironment.isEmpty() ? null : hash(fromEnvironment, iterations);
        if (configuration.isResetPassword()) {
            reset();
        }
        this.stored = load();
        if (environment != null) {
            LOGGER.info("The dashboard password comes from CLIENT_PASSWORD");
        } else if (stored == null) {
            LOGGER.warn("No dashboard password is set, the first person to open the dashboard can set it");
        }
    }

    public boolean isConfigured() {
        return environment != null || currentStored() != null;
    }

    public boolean isFromEnvironment() {
        return environment != null;
    }

    public boolean matches(String password) {
        StoredHash expected = environment != null ? environment : currentStored();
        if (expected == null || password == null || password.length() > MAX_LENGTH) {
            return false;
        }
        byte[] actual = derive(password, Base64.getDecoder().decode(expected.salt()), expected.iterations());
        return MessageDigest.isEqual(actual, Base64.getDecoder().decode(expected.hash()));
    }

    public synchronized void setInitial(String password) {
        if (environment != null) {
            throw new ConflictException("The password is set by CLIENT_PASSWORD and cannot be changed here");
        }
        if (stored != null) {
            throw new ConflictException("A password is already set");
        }
        if (password == null || password.length() < MIN_LENGTH) {
            throw new InvalidRequestException("The password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw new InvalidRequestException("The password must be at most " + MAX_LENGTH + " characters");
        }
        StoredHash created = hash(password, iterations);
        try {
            OwnerOnlyFiles.write(file, mapper.writeValueAsBytes(created));
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to save the dashboard password to " + file, e);
        }
        stored = created;
        LOGGER.info("The dashboard password was set");
    }

    private synchronized StoredHash currentStored() {
        return stored;
    }

    private void reset() {
        try {
            if (Files.deleteIfExists(file)) {
                LOGGER.warn("CLIENT_RESET_PASSWORD is set: the stored dashboard password was removed, the first person to open the dashboard can set a new one. Remove CLIENT_RESET_PASSWORD now");
            } else {
                LOGGER.warn("CLIENT_RESET_PASSWORD is set but no password was stored. Remove it");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not remove the stored dashboard password " + file + ": " + e.getMessage(), e);
        }
    }

    private StoredHash load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            StoredHash loaded = mapper.readValue(file.toFile(), StoredHash.class);
            if (loaded == null || loaded.salt() == null || loaded.hash() == null || loaded.iterations() < 1) {
                throw new IOException("the file is incomplete");
            }
            Base64.getDecoder().decode(loaded.salt());
            Base64.getDecoder().decode(loaded.hash());
            return loaded;
        } catch (IOException | IllegalArgumentException | JacksonException e) {
            throw new IllegalStateException("Could not read the stored dashboard password " + file + ": " + e.getMessage()
                + ". Fix or delete the file, or start once with CLIENT_RESET_PASSWORD=true", e);
        }
    }

    private StoredHash hash(String password, int rounds) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, rounds);
        return new StoredHash(rounds, Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(derived));
    }

    private static byte[] derive(String password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is unavailable: " + e.getMessage(), e);
        } finally {
            spec.clearPassword();
        }
    }
}
