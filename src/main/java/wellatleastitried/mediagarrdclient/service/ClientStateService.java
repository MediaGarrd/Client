package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public synchronized void updateKnownServers(List<String> servers) {
        knownServers.clear();
        knownServers.addAll(servers);
        if (isBlank(activeServer)) {
            activeServer = knownServers.stream().findFirst().orElse(null);
        } else {
            knownServers.add(activeServer);
        }
        LOGGER.info("Known servers updated: {}, active server: {}", knownServers, activeServer);
        save();
    }

    public synchronized void setActiveServer(String serverUrl) {
        LOGGER.info("Active server changed: {} -> {}", activeServer, serverUrl);
        activeServer = serverUrl;
        if (!isBlank(serverUrl)) {
            knownServers.add(serverUrl);
        }
        save();
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
