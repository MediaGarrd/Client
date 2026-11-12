package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import wellatleastitried.mediagarrdclient.Configuration;

@Service
public class ClientStateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientStateService.class);
    private static final Duration DEFAULT_PICKUP_INTERVAL = Duration.ofHours(12);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientState(String activeServer, long pickupIntervalSeconds, List<String> knownServers) {
    }

    private final Path statePath;
    private final ObjectMapper mapper;
    private final Set<String> knownServers = new LinkedHashSet<>();
    private final List<Consumer<String>> activeServerListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> knownServersListeners = new CopyOnWriteArrayList<>();
    private String activeServer;
    private Duration pickupInterval;

    public ClientStateService(Configuration configuration, ObjectMapper mapper) {
        this.statePath = Path.of(configuration.getStateFile()).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.pickupInterval = valid(configuration.getPickupInterval()) ? configuration.getPickupInterval() : DEFAULT_PICKUP_INTERVAL;
        load();
        LOGGER.info("Client state: activeServer={}, pickupInterval={}, knownServers={}", activeServer, pickupInterval, knownServers);
    }

    public synchronized String getActiveServer() {
        return activeServer;
    }

    public synchronized String requireActiveServer() {
        if (isBlank(activeServer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active MediaGarrd server selected");
        }
        return activeServer;
    }

    public synchronized Duration getPickupInterval() {
        return pickupInterval;
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
                knownServers.clear();
                knownServers.addAll(servers);
                if (isBlank(activeServer)) {
                    activeServer = knownServers.stream().findFirst().orElse(null);
                } else {
                    knownServers.add(activeServer);
                }
                after = activeServer;
                listAfter = List.copyOf(knownServers);
                LOGGER.info("Known servers updated: {}, active server: {}", knownServers, activeServer);
                save();
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

    public void setActiveServer(String serverUrl) {
        boolean changed = false;
        boolean listChanged = false;
        try {
            synchronized (this) {
                LOGGER.info("Active server changed: {} -> {}", activeServer, serverUrl);
                changed = !Objects.equals(activeServer, serverUrl);
                activeServer = serverUrl;
                if (!isBlank(serverUrl)) {
                    listChanged = knownServers.add(serverUrl);
                }
                save();
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

    public void setActiveServerIfBlank(String serverUrl) {
        boolean changed = false;
        boolean listChanged = false;
        try {
            synchronized (this) {
                if (!isBlank(activeServer) || isBlank(serverUrl)) {
                    return;
                }
                LOGGER.info("Active server changed: {} -> {}", activeServer, serverUrl);
                activeServer = serverUrl;
                changed = true;
                listChanged = knownServers.add(serverUrl);
                save();
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

    public void addActiveServerListener(Consumer<String> listener) {
        activeServerListeners.add(listener);
    }

    public void addKnownServersListener(Runnable listener) {
        knownServersListeners.add(listener);
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
        if (!valid(interval)) {
            throw new IllegalArgumentException("Pickup interval must be a positive duration");
        }
        pickupInterval = interval;
        LOGGER.info("Pickup interval updated to {}", interval);
        save();
        return interval;
    }

    private void load() {
        if (!Files.exists(statePath)) {
            LOGGER.info("No state file at {}, starting fresh", statePath);
            return;
        }
        try {
            ClientState state = mapper.readValue(statePath.toFile(), ClientState.class);
            if (state.knownServers() != null) {
                knownServers.addAll(state.knownServers());
            }
            activeServer = isBlank(state.activeServer()) ? knownServers.stream().findFirst().orElse(null) : state.activeServer();
            Duration saved = Duration.ofSeconds(state.pickupIntervalSeconds());
            if (valid(saved)) {
                pickupInterval = saved;
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read state from {}, starting with defaults: {}", statePath, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(statePath.getParent());
            Path temp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            ClientState state = new ClientState(activeServer == null ? "" : activeServer, pickupInterval.toSeconds(), new ArrayList<>(knownServers));
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
            Files.move(temp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save client state to " + statePath, e);
        }
    }

    private static boolean valid(Duration duration) {
        return duration != null && duration.isPositive();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
