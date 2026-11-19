package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.ObjectMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;

@Service
public class ClientStateService implements ServerTokens {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientStateService.class);
    private static final Duration DEFAULT_PICKUP_INTERVAL = Duration.ofHours(12);
    static final Duration MAX_PICKUP_INTERVAL = Duration.ofDays(365);
    private static final DateTimeFormatter ASIDE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientState(String activeServer, long pickupIntervalSeconds, Instant lastPickupAt, List<String> knownServers, Map<String, StoredPairing> pairings) {
    }

    private final Path statePath;
    private final ObjectMapper mapper;
    private final Set<String> knownServers = new LinkedHashSet<>();
    private final List<Consumer<String>> activeServerListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> knownServersListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> pairingListeners = new CopyOnWriteArrayList<>();
    private final Map<String, StoredPairing> pairings = new LinkedHashMap<>();
    private String activeServer;
    private Duration pickupInterval;
    private Instant lastPickupAt;

    public ClientStateService(MediaGarrdProperties configuration, ObjectMapper mapper) {
        this.statePath = Path.of(configuration.getStateFile()).toAbsolutePath().normalize();
        this.mapper = mapper;
        Duration configured = configuration.getPickupInterval();
        if (valid(configured)) {
            this.pickupInterval = configured;
        } else {
            LOGGER.warn("The configured pickup interval {} is outside 1 second to 365 days, using {}", configured, DEFAULT_PICKUP_INTERVAL);
            this.pickupInterval = DEFAULT_PICKUP_INTERVAL;
        }
        load();
        LOGGER.info("Client state: activeServer={}, pickupInterval={}, knownServers={}, pairedServers={}", activeServer, pickupInterval, knownServers, pairings.size());
    }

    public synchronized String getActiveServer() {
        return activeServer;
    }

    public synchronized String requireActiveServer() {
        if (!StringUtils.hasText(activeServer)) {
            throw new InvalidRequestException("No active MediaGarrd server selected");
        }
        return activeServer;
    }

    public synchronized Duration getPickupInterval() {
        return pickupInterval;
    }

    public synchronized Instant getLastPickupAt() {
        return lastPickupAt;
    }

    public synchronized boolean recordPickup(String server, Instant at) {
        if (!Objects.equals(server, activeServer)) {
            return false;
        }
        persist(activeServer, pickupInterval, knownServers, pairings, at);
        lastPickupAt = at;
        return true;
    }

    public synchronized List<String> getKnownServers() {
        return List.copyOf(knownServers);
    }

    public void updateKnownServers(List<String> servers) {
        String before = null;
        String after = null;
        List<String> listBefore = null;
        List<String> listAfter = null;
        try {
            synchronized (this) {
                before = activeServer;
                listBefore = List.copyOf(knownServers);
                List<String> found = servers.stream().map(ServerUrls::normalize).toList();
                Set<String> nextKnown = new LinkedHashSet<>(knownServers);
                nextKnown.addAll(found);
                String nextActive = activeServer;
                if (!StringUtils.hasText(nextActive)) {
                    nextActive = found.stream().findFirst().orElse(null);
                } else {
                    nextKnown.add(nextActive);
                }
                persist(nextActive, pickupInterval, nextKnown, pairings, lastPickupAt);
                knownServers.clear();
                knownServers.addAll(nextKnown);
                activeServer = nextActive;
                after = activeServer;
                listAfter = List.copyOf(knownServers);
                LOGGER.info("Known servers updated: {}, active server: {}", knownServers, activeServer);
            }
        } finally {
            if (!Objects.equals(before, after)) {
                notifyActiveServerListeners(after);
            }
            if (listBefore != null && !listBefore.equals(listAfter)) {
                notifyKnownServersListeners();
            }
        }
    }

    public void setActiveServer(String requested) {
        String serverUrl = ServerUrls.normalize(requested);
        boolean changed = false;
        boolean listChanged = false;
        try {
            synchronized (this) {
                changed = !Objects.equals(activeServer, serverUrl);
                Set<String> nextKnown = new LinkedHashSet<>(knownServers);
                if (StringUtils.hasText(serverUrl)) {
                    listChanged = nextKnown.add(serverUrl);
                }
                Instant nextPickup = changed ? null : lastPickupAt;
                persist(serverUrl, pickupInterval, nextKnown, pairings, nextPickup);
                LOGGER.info("Active server changed: {} -> {}", activeServer, serverUrl);
                activeServer = serverUrl;
                lastPickupAt = nextPickup;
                knownServers.clear();
                knownServers.addAll(nextKnown);
            }
        } finally {
            if (changed) {
                notifyActiveServerListeners(serverUrl);
            }
            if (listChanged) {
                notifyKnownServersListeners();
            }
        }
    }

    public void setActiveServerIfBlank(String requested) {
        String serverUrl = ServerUrls.normalize(requested);
        boolean changed = false;
        boolean listChanged = false;
        try {
            synchronized (this) {
                if (StringUtils.hasText(activeServer) || !StringUtils.hasText(serverUrl)) {
                    return;
                }
                Set<String> nextKnown = new LinkedHashSet<>(knownServers);
                listChanged = nextKnown.add(serverUrl);
                Instant nextPickup = changed ? null : lastPickupAt;
                persist(serverUrl, pickupInterval, nextKnown, pairings, nextPickup);
                LOGGER.info("Active server changed: {} -> {}", activeServer, serverUrl);
                activeServer = serverUrl;
                lastPickupAt = nextPickup;
                changed = true;
                knownServers.clear();
                knownServers.addAll(nextKnown);
            }
        } finally {
            if (changed) {
                notifyActiveServerListeners(serverUrl);
            }
            if (listChanged) {
                notifyKnownServersListeners();
            }
        }
    }

    public synchronized boolean isKnownServer(String serverUrl) {
        return knownServers.contains(ServerUrls.normalize(serverUrl));
    }

    public void addActiveServerListener(Consumer<String> listener) {
        activeServerListeners.add(listener);
    }

    public void addKnownServersListener(Runnable listener) {
        knownServersListeners.add(listener);
    }

    public void addPairingListener(Consumer<String> listener) {
        pairingListeners.add(listener);
    }

    @Override
    public synchronized Optional<String> tokenFor(String serverUrl) {
        return pairingFor(serverUrl).map(StoredPairing::token);
    }

    public synchronized Optional<StoredPairing> pairingFor(String serverUrl) {
        return Optional.ofNullable(pairings.get(ServerUrls.normalize(serverUrl)));
    }

    public synchronized boolean hasPairing(String serverUrl) {
        return pairings.containsKey(ServerUrls.normalize(serverUrl));
    }

    public void storePairing(String serverUrl, StoredPairing pairing) {
        String key = ServerUrls.normalize(serverUrl);
        synchronized (this) {
            Map<String, StoredPairing> next = new LinkedHashMap<>(pairings);
            next.put(key, pairing);
            persist(activeServer, pickupInterval, knownServers, next, lastPickupAt);
            LOGGER.info("Paired with {} as {}", key, pairing.name());
            pairings.put(key, pairing);
        }
        notifyPairingListeners(key);
    }

    public boolean forgetPairing(String serverUrl) {
        String key = ServerUrls.normalize(serverUrl);
        synchronized (this) {
            if (!pairings.containsKey(key)) {
                return false;
            }
            Map<String, StoredPairing> next = new LinkedHashMap<>(pairings);
            next.remove(key);
            persist(activeServer, pickupInterval, knownServers, next, lastPickupAt);
            pairings.remove(key);
            LOGGER.info("Forgot the pairing with {}", key);
        }
        notifyPairingListeners(key);
        return true;
    }

    public boolean forgetPairingIf(String serverUrl, String token) {
        String key = ServerUrls.normalize(serverUrl);
        synchronized (this) {
            StoredPairing current = pairings.get(key);
            if (current == null || !current.token().equals(token)) {
                return false;
            }
            Map<String, StoredPairing> next = new LinkedHashMap<>(pairings);
            next.remove(key);
            persist(activeServer, pickupInterval, knownServers, next, lastPickupAt);
            pairings.remove(key);
            LOGGER.info("Forgot the pairing with {}", key);
        }
        notifyPairingListeners(key);
        return true;
    }

    private void notifyPairingListeners(String serverUrl) {
        for (Consumer<String> listener : pairingListeners) {
            try {
                listener.accept(serverUrl);
            } catch (RuntimeException e) {
                LOGGER.warn("Pairing listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyKnownServersListeners() {
        for (Runnable listener : knownServersListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.warn("Known servers listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyActiveServerListeners(String serverUrl) {
        for (Consumer<String> listener : activeServerListeners) {
            try {
                listener.accept(serverUrl);
            } catch (RuntimeException e) {
                LOGGER.warn("Active server listener failed: {}", e.getMessage());
            }
        }
    }

    public synchronized Duration setPickupInterval(Duration interval) {
        if (interval == null || !interval.isPositive()) {
            throw new InvalidRequestException("Pickup interval must be a positive duration");
        }
        if (interval.compareTo(MAX_PICKUP_INTERVAL) > 0) {
            throw new InvalidRequestException("Pickup interval must be at most 365 days");
        }
        persist(activeServer, interval, knownServers, pairings, lastPickupAt);
        pickupInterval = interval;
        LOGGER.info("Pickup interval updated to {}", interval);
        return interval;
    }

    private void load() {
        if (!Files.exists(statePath)) {
            LOGGER.info("No state file at {}, starting fresh", statePath);
            return;
        }
        if (!Files.isReadable(statePath)) {
            throw unreadable(null);
        }
        try {
            ClientState state = mapper.readValue(statePath.toFile(), ClientState.class);
            if (state == null) {
                throw new IllegalStateException("the file is empty");
            }
            if (state.knownServers() != null) {
                state.knownServers().stream().map(ServerUrls::normalize).forEach(knownServers::add);
            }
            if (state.pairings() != null) {
                state.pairings().forEach((url, pairing) -> {
                    if (pairing != null && StringUtils.hasText(pairing.token())) {
                        pairings.put(ServerUrls.normalize(url), pairing);
                    }
                });
            }
            activeServer = !StringUtils.hasText(state.activeServer()) ? knownServers.stream().findFirst().orElse(null) : ServerUrls.normalize(state.activeServer());
            Duration saved = Duration.ofSeconds(state.pickupIntervalSeconds());
            if (valid(saved)) {
                pickupInterval = saved;
            } else if (state.pickupIntervalSeconds() != 0) {
                LOGGER.warn("Ignoring the stored pickup interval of {} seconds, it is outside 1 second to 365 days. Using {}", state.pickupIntervalSeconds(), pickupInterval);
            }
            lastPickupAt = state.lastPickupAt();
        } catch (JacksonIOException e) {
            throw unreadable(e);
        } catch (JacksonException | IllegalStateException e) {
            moveAside(e);
        }
    }

    private IllegalStateException unreadable(Exception cause) {
        String detail = cause == null ? "it is not readable" : cause.getMessage();
        return new IllegalStateException("Could not read the client state file " + statePath + " as " + runUser() + ": " + detail
            + ". Fix its owner or permissions so that this user can read it (or run the container with the matching PUID and PGID); the file is left untouched", cause);
    }

    private static String runUser() {
        try {
            return "UID " + Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        } catch (IOException | RuntimeException e) {
            return "user " + System.getProperty("user.name");
        }
    }

    private void moveAside(Exception cause) {
        Path aside = statePath.resolveSibling(statePath.getFileName() + ".corrupt-" + ASIDE_STAMP.format(Instant.now()));
        try {
            Files.move(statePath, aside, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.error("Could not read the client state from {} ({}). The file was moved to {} and the client starts without pairings, so every server needs to be paired again",
                statePath, cause.getMessage(), aside);
        } catch (IOException e) {
            LOGGER.error("Could not read the client state from {} ({}) and could not move it aside ({}). The client starts without pairings and the next change overwrites the file",
                statePath, cause.getMessage(), e.getMessage());
        }
    }

    private void persist(String active, Duration interval, Collection<String> known, Map<String, StoredPairing> paired, Instant lastPickup) {
        try {
            ClientState state = new ClientState(active == null ? "" : active, interval.toSeconds(), lastPickup, new ArrayList<>(known), new LinkedHashMap<>(paired));
            OwnerOnlyFiles.write(statePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to save client state to " + statePath, e);
        }
    }

    private static boolean valid(Duration duration) {
        return duration != null && duration.isPositive() && duration.compareTo(MAX_PICKUP_INTERVAL) <= 0;
    }
}
