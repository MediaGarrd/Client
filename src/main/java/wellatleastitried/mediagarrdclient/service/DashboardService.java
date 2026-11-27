package wellatleastitried.mediagarrdclient.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.dto.DashboardState;
import wellatleastitried.mediagarrdclient.dto.HealthSummary;
import wellatleastitried.mediagarrdclient.dto.LinkState;
import wellatleastitried.mediagarrdclient.dto.PairingState;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;

@Service
public class DashboardService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardService.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(7);
    private static final Duration CATCH_ALL_INTERVAL = Duration.ofSeconds(60);

    private final ClientStateService stateService;
    private final VersionService versionService;
    private final UpdateCheckService updateCheckService;
    private final ServerApiService serverApiService;
    private final Executor executor;
    private final Clock clock;
    private final Object lock = new Object();
    private final List<Consumer<String>> recoveryListeners = new CopyOnWriteArrayList<>();
    private final DashboardEvents events;
    private final PairingStatus pairingStatus;
    private final LinkStateMachine links;
    private final BackupWatcher backups;
    private final CoalescedRefresh healthRefresh;
    private final CoalescedRefresh versionRefresh;
    private String activeServer;
    private List<String> knownServers = List.of();
    private HealthSummary health;
    private Instant healthFetchedAt;
    private long epoch;

    @Autowired
    public DashboardService(
        ClientStateService stateService,
        ClientEventPublisher broadcaster,
        ServerApiService serverApiService,
        VersionService versionService,
        UpdateCheckService updateCheckService,
        ObjectMapper mapper
    ) {
        this(stateService, broadcaster, serverApiService, versionService, updateCheckService, mapper, refreshThreads(), Clock.systemUTC());
    }

    DashboardService(
        ClientStateService stateService,
        ClientEventPublisher broadcaster,
        ServerApiService serverApiService,
        VersionService versionService,
        UpdateCheckService updateCheckService,
        ObjectMapper mapper,
        Executor executor,
        Clock clock
    ) {
        this.stateService = stateService;
        this.serverApiService = serverApiService;
        this.versionService = versionService;
        this.updateCheckService = updateCheckService;
        this.executor = executor;
        this.clock = clock;
        this.events = new DashboardEvents(broadcaster, mapper);
        this.pairingStatus = new PairingStatus(stateService, events);
        this.links = new LinkStateMachine(events, clock);
        this.backups = new BackupWatcher(serverApiService, events, clock, lock, executor, CATCH_ALL_INTERVAL, () -> activeServer, () -> epoch, () -> links.link() == LinkState.DEGRADED);
        this.healthRefresh = new CoalescedRefresh(lock, executor, this::fetchHealth);
        this.versionRefresh = new CoalescedRefresh(lock, executor, this::fetchVersion);
    }

    private static ExecutorService refreshThreads() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> Thread.ofPlatform().daemon().name("dashboard-refresh-" + counter.incrementAndGet()).unstarted(runnable));
    }

    @PostConstruct
    void start() {
        stateService.addKnownServersListener(this::sync);
        stateService.addActiveServerListener(ignored -> sync());
        stateService.addPairingListener(this::pairingChanged);
        serverApiService.addTokenRejectedListener(this::pairingRejected);
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

    public <T> Optional<T> subscribe(Function<List<ClientEvent>, Optional<T>> registration) {
        synchronized (lock) {
            return registration.apply(List.of(new ClientEvent("snapshot", events.json(snapshot()))));
        }
    }

    void sync() {
        synchronized (lock) {
            String active = ServerUrls.nullIfBlank(stateService.getActiveServer());
            List<String> known = stateService.getKnownServers();
            if (!Objects.equals(active, activeServer)) {
                knownServers = known;
                reset(active);
            } else if (!known.equals(knownServers)) {
                knownServers = known;
                publishServers();
            }
        }
    }

    private void reset(String active) {
        activeServer = active;
        links.reset(active != null);
        health = null;
        healthFetchedAt = null;
        backups.forget();
        pairingStatus.clear();
        epoch++;
        versionService.select(active);
        events.publish("snapshot", snapshot());
        if (active != null) {
            healthRefresh.request();
        }
    }

    void pairingChanged(String server) {
        synchronized (lock) {
            if (Objects.equals(ServerUrls.nullIfBlank(server), activeServer)) {
                reset(activeServer);
            }
        }
    }

    void pairingRejected(String server) {
        synchronized (lock) {
            if (!Objects.equals(server, activeServer)) {
                return;
            }
            pairingStatus.reject(activeServer);
        }
    }

    void streamUp(String server) {
        synchronized (lock) {
            if (!Objects.equals(server, activeServer)) {
                return;
            }
            boolean wasDown = links.link() == LinkState.DOWN;
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
            links.setRunning(runningNow);
            events.publishRaw(name, data);
        }
    }

    void backupsChanged(String server, String data) {
        synchronized (lock) {
            if (Objects.equals(server, activeServer)) {
                events.publishRaw("backups-changed", data);
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
            Duration limit = links.link() == LinkState.LIVE ? CATCH_ALL_INTERVAL : POLL_INTERVAL;
            boolean due = healthFetchedAt == null || Duration.between(healthFetchedAt, clock.instant()).compareTo(limit) >= 0;
            if (due && !healthRefresh.busy()) {
                healthRefresh.request();
            }
            if (links.reachable() && !versionRefresh.busy() && versionService.needsRefresh()) {
                versionRefresh.request();
            }
            if (links.link() == LinkState.DEGRADED) {
                backups.requestWhenDue();
            }
        }
    }

    public void addRecoveryListener(Consumer<String> listener) {
        recoveryListeners.add(listener);
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
        boolean tokenRejected = false;
        try {
            status = serverApiService.health(server);
        } catch (PairingRequiredException e) {
            tokenRejected = true;
            status = new ServerStatusDto(true, false, 0, null);
        } catch (RuntimeException e) {
            LOGGER.debug("{} did not answer the health check: {}", server, e.getMessage());
        }
        applyHealth(expected, status, tokenRejected);
    }

    private void applyHealth(long expected, ServerStatusDto status, boolean tokenRejected) {
        String cleared;
        synchronized (lock) {
            cleared = applyHealthLocked(expected, status, tokenRejected);
        }
        if (cleared != null) {
            for (Consumer<String> listener : recoveryListeners) {
                listener.accept(cleared);
            }
        }
    }

    private String applyHealthLocked(long expected, ServerStatusDto status, boolean tokenRejected) {
        String cleared = null;
        if (expected != epoch) {
            return null;
        }
        healthFetchedAt = clock.instant();
        if (status != null && pairingStatus.observe(tokenRejected, activeServer)) {
            cleared = activeServer;
        }
        boolean detailed = status != null && pairingStatus.of(activeServer) == PairingState.PAIRED;
        if (status == null) {
            if (links.link() != LinkState.LIVE) {
                setLink(LinkState.DOWN);
                if (versionService.markUnreachable()) {
                    events.publish("versions", versionService.versions());
                }
            }
            return cleared;
        }
        if (detailed) {
            HealthSummary summary = new HealthSummary(status.backupInterval(), status.backupRetentionCount());
            if (!summary.equals(health)) {
                health = summary;
                events.publish("config", summary);
            }
        }
        if (links.link() != LinkState.LIVE) {
            boolean recovered = links.link() != LinkState.DEGRADED;
            boolean wasDown = links.link() == LinkState.DOWN;
            setLink(LinkState.DEGRADED);
            if (detailed && links.setPolledRunning(status.running())) {
                backups.forgetListing();
            }
            if (recovered && versionService.invalidate(wasDown)) {
                versionRefresh.request();
            }
        }
        return cleared;
    }

    private void fetchVersion() {
        if (versionService.refresh()) {
            publishVersions();
        }
    }

    private void publishVersions() {
        synchronized (lock) {
            events.publish("versions", versionService.versions());
        }
    }

    private void publishServers() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knownServers", knownServers);
        payload.put("activeServer", activeServer);
        events.publish("servers", payload);
    }

    private void setLink(LinkState next) {
        if (links.setLink(next, activeServer) && next != LinkState.DEGRADED) {
            backups.forget();
        }
    }

    private DashboardState snapshot() {
        return new DashboardState(activeServer, knownServers, links.link(), links.running(), health, versionService.versions(), pairingStatus.of(activeServer));
    }
}
