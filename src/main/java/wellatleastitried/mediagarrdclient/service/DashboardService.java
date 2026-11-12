package wellatleastitried.mediagarrdclient.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.DashboardState;
import wellatleastitried.mediagarrdclient.dto.HealthSummary;
import wellatleastitried.mediagarrdclient.dto.LinkState;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;

@Service
public class DashboardService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardService.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(7);
    private static final Duration CATCH_ALL_INTERVAL = Duration.ofSeconds(60);

    private final class Refresh {

        private final Runnable work;
        private boolean inFlight;
        private boolean again;

        private Refresh(Runnable work) {
            this.work = work;
        }

        private void request() {
            synchronized (lock) {
                if (inFlight) {
                    again = true;
                    return;
                }
                inFlight = true;
            }
            try {
                executor.execute(this::loop);
            } catch (RejectedExecutionException e) {
                synchronized (lock) {
                    inFlight = false;
                }
            }
        }

        private void loop() {
            boolean repeat;
            do {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOGGER.warn("Dashboard refresh failed: {}", e.getMessage());
                }
                synchronized (lock) {
                    repeat = again;
                    again = false;
                    if (!repeat) {
                        inFlight = false;
                    }
                }
            } while (repeat);
        }

        private boolean busy() {
            return inFlight;
        }
    }

    private final ClientStateService stateService;
    private final ClientEventBroadcaster broadcaster;
    private final ServerApiService serverApiService;
    private final VersionService versionService;
    private final UpdateCheckService updateCheckService;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final Clock clock;
    private final Object lock = new Object();
    private final Refresh healthRefresh = new Refresh(this::fetchHealth);
    private final Refresh versionRefresh = new Refresh(this::fetchVersion);
    private final Refresh backupsRefresh = new Refresh(this::fetchBackups);
    private String activeServer;
    private List<String> knownServers = List.of();
    private LinkState link = LinkState.DOWN;
    private Boolean running;
    private HealthSummary health;
    private Instant healthFetchedAt;
    private List<BackupDto> backupsSeen;
    private Instant backupsCheckedAt;
    private long epoch;

    @Autowired
    public DashboardService(
        ClientStateService stateService,
        ClientEventBroadcaster broadcaster,
        ServerApiService serverApiService,
        VersionService versionService,
        UpdateCheckService updateCheckService,
        ObjectMapper mapper
    ) {
        this(stateService, broadcaster, serverApiService, versionService, updateCheckService, mapper, refreshThreads(), Clock.systemUTC());
    }

    DashboardService(
        ClientStateService stateService,
        ClientEventBroadcaster broadcaster,
        ServerApiService serverApiService,
        VersionService versionService,
        UpdateCheckService updateCheckService,
        ObjectMapper mapper,
        Executor executor,
        Clock clock
    ) {
        this.stateService = stateService;
        this.broadcaster = broadcaster;
        this.serverApiService = serverApiService;
        this.versionService = versionService;
        this.updateCheckService = updateCheckService;
        this.mapper = mapper;
        this.executor = executor;
        this.clock = clock;
    }

    private static ExecutorService refreshThreads() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> Thread.ofPlatform().daemon().name("dashboard-refresh-" + counter.incrementAndGet()).unstarted(runnable));
    }

    @PostConstruct
    void start() {
        stateService.addKnownServersListener(this::sync);
        updateCheckService.addStatusListener(ignored -> publishVersions());
        sync();
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    public DashboardState state() {
        synchronized (lock) {
            return snapshot();
        }
    }

    public Optional<SseEmitter> subscribe() {
        synchronized (lock) {
            return broadcaster.subscribe(List.of(new ClientEvent("snapshot", json(snapshot()))));
        }
    }

    void sync() {
        synchronized (lock) {
            String active = normalize(stateService.getActiveServer());
            List<String> known = stateService.getKnownServers();
            if (!Objects.equals(active, activeServer)) {
                activeServer = active;
                knownServers = known;
                link = active == null ? LinkState.DOWN : LinkState.CONNECTING;
                running = null;
                health = null;
                healthFetchedAt = null;
                backupsSeen = null;
                backupsCheckedAt = null;
                epoch++;
                versionService.select(active);
                broadcaster.publish("snapshot", json(snapshot()));
                if (active != null) {
                    healthRefresh.request();
                }
            } else if (!known.equals(knownServers)) {
                knownServers = known;
                publishServers();
            }
        }
    }

    void streamUp(String server) {
        synchronized (lock) {
            if (!Objects.equals(server, activeServer)) {
                return;
            }
            boolean wasDown = link == LinkState.DOWN;
            setLink(LinkState.LIVE);
            healthRefresh.request();
            if (versionService.invalidate(wasDown)) {
                versionRefresh.request();
            }
        }
    }

    void streamDown(String server) {
        synchronized (lock) {
            if (!Objects.equals(server, activeServer)) {
                return;
            }
            setLink(LinkState.CONNECTING);
            healthRefresh.request();
        }
    }

    void runEvent(String server, String name, String data, boolean runningNow) {
        synchronized (lock) {
            if (!Objects.equals(server, activeServer)) {
                return;
            }
            running = runningNow;
            broadcaster.publish(name, data);
        }
    }

    void backupsChanged(String server, String data) {
        synchronized (lock) {
            if (Objects.equals(server, activeServer)) {
                broadcaster.publish("backups-changed", data);
            }
        }
    }

    void configChanged(String server) {
        synchronized (lock) {
            if (Objects.equals(server, activeServer)) {
                healthRefresh.request();
            }
        }
    }

    @Scheduled(initialDelayString = "PT8S", fixedDelayString = "PT8S")
    void tick() {
        synchronized (lock) {
            if (activeServer == null) {
                return;
            }
            Duration limit = link == LinkState.LIVE ? CATCH_ALL_INTERVAL : POLL_INTERVAL;
            boolean due = healthFetchedAt == null || Duration.between(healthFetchedAt, clock.instant()).compareTo(limit) >= 0;
            if (due && !healthRefresh.busy()) {
                healthRefresh.request();
            }
            if (reachable() && !versionRefresh.busy() && versionService.needsRefresh()) {
                versionRefresh.request();
            }
            if (link == LinkState.DEGRADED && !backupsRefresh.busy() && (backupsCheckedAt == null || Duration.between(backupsCheckedAt, clock.instant()).compareTo(CATCH_ALL_INTERVAL) >= 0)) {
                backupsRefresh.request();
            }
        }
    }

    private void fetchHealth() {
        String server;
        long expected;
        synchronized (lock) {
            server = activeServer;
            expected = epoch;
        }
        if (server == null) {
            return;
        }
        ServerStatusDto status = null;
        try {
            status = serverApiService.health(server);
        } catch (RuntimeException e) {
            LOGGER.debug("{} did not answer the health check: {}", server, e.getMessage());
        }
        applyHealth(expected, status);
    }

    private void applyHealth(long expected, ServerStatusDto status) {
        synchronized (lock) {
            if (expected != epoch) {
                return;
            }
            healthFetchedAt = clock.instant();
            if (status == null) {
                if (link != LinkState.LIVE) {
                    setLink(LinkState.DOWN);
                    if (versionService.markUnreachable()) {
                        broadcaster.publish("versions", json(versionService.versions()));
                    }
                }
                return;
            }
            HealthSummary summary = new HealthSummary(status.backupInterval(), status.backupRetentionCount());
            if (!summary.equals(health)) {
                health = summary;
                broadcaster.publish("config", json(summary));
            }
            if (link != LinkState.LIVE) {
                boolean recovered = link != LinkState.DEGRADED;
                boolean wasDown = link == LinkState.DOWN;
                setLink(LinkState.DEGRADED);
                setPolledRunning(status.running());
                if (recovered && versionService.invalidate(wasDown)) {
                    versionRefresh.request();
                }
            }
        }
    }

    private void fetchBackups() {
        String server;
        long expected;
        synchronized (lock) {
            server = activeServer;
            expected = epoch;
        }
        if (server == null) {
            return;
        }
        List<BackupDto> listed;
        try {
            listed = serverApiService.listBackups(server);
        } catch (RuntimeException e) {
            LOGGER.debug("{} did not list its backups: {}", server, e.getMessage());
            listed = null;
        }
        synchronized (lock) {
            if (expected != epoch || link != LinkState.DEGRADED) {
                return;
            }
            backupsCheckedAt = clock.instant();
            if (listed == null) {
                return;
            }
            if (backupsSeen != null && !listed.equals(backupsSeen)) {
                broadcaster.publish("backups-changed", json(Map.of("at", clock.instant().toString())));
            }
            backupsSeen = listed;
        }
    }

    private boolean reachable() {
        return link == LinkState.LIVE || link == LinkState.DEGRADED;
    }

    private void fetchVersion() {
        if (versionService.refresh()) {
            publishVersions();
        }
    }

    private void publishVersions() {
        synchronized (lock) {
            broadcaster.publish("versions", json(versionService.versions()));
        }
    }

    private void publishServers() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knownServers", knownServers);
        payload.put("activeServer", activeServer);
        broadcaster.publish("servers", json(payload));
    }

    private void setLink(LinkState next) {
        if (link == next) {
            return;
        }
        link = next;
        if (!reachable()) {
            running = null;
        }
        if (next != LinkState.DEGRADED) {
            backupsSeen = null;
            backupsCheckedAt = null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server", activeServer == null ? "" : activeServer);
        payload.put("state", next);
        broadcaster.publish("link", json(payload));
    }

    private void setPolledRunning(boolean polled) {
        if (Boolean.valueOf(polled).equals(running)) {
            return;
        }
        if (Boolean.TRUE.equals(running) && !polled) {
            backupsSeen = null;
        }
        running = polled;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("running", polled);
        payload.put("at", clock.instant().toString());
        broadcaster.publish("state", json(payload));
    }

    private DashboardState snapshot() {
        return new DashboardState(activeServer, knownServers, link, running, health, versionService.versions());
    }

    private String json(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String url) {
        return url == null || url.isBlank() ? null : url;
    }
}
