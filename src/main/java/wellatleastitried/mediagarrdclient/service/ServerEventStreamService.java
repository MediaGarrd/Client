package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.protocol.Endpoints;

@Service
public class ServerEventStreamService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerEventStreamService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HEADER_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WATCHDOG = Duration.ofSeconds(45);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration UNSUPPORTED_RETRY = Duration.ofMinutes(5);
    private static final Duration MIN_STREAM_LIFETIME = Duration.ofSeconds(5);
    private static final Set<String> FORWARDED_EVENTS = Set.of("state", "run-started", "run-finished", "backups-changed", "config-changed");

    public record Timing(Duration headerTimeout, Duration watchdog, Duration initialBackoff, Duration maxBackoff, Duration unsupportedRetry, Duration minStreamLifetime) {
    }

    private final class Subscription implements Runnable {

        private final String serverUrl;
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private volatile CompletableFuture<HttpResponse<InputStream>> pending;
        private volatile InputStream body;
        private volatile long lastActivityNanos;
        private volatile boolean rejected;
        private boolean linkUp;
        private boolean eventsSeen;
        private Thread thread;

        private Subscription(String serverUrl) {
            this.serverUrl = serverUrl;
        }

        private boolean isCancelled() {
            return cancelled.getCount() == 0;
        }

        private void cancel() {
            cancelled.countDown();
            CompletableFuture<HttpResponse<InputStream>> future = pending;
            if (future != null) {
                future.cancel(true);
            }
            closeBody();
            Thread worker = thread;
            if (worker != null) {
                worker.interrupt();
            }
        }

        private void closeBody() {
            InputStream stream = body;
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException | RuntimeException e) {
                    LOGGER.debug("Closing event stream failed: {}", e.getMessage());
                }
            }
        }

        private boolean pause(Duration delay) {
            try {
                return cancelled.await(delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return true;
            }
        }

        @Override
        public void run() {
            ReconnectPolicy policy = new ReconnectPolicy(timing.initialBackoff(), timing.maxBackoff(), timing.unsupportedRetry(), timing.minStreamLifetime());
            try {
                while (!isCancelled()) {
                    ReconnectPolicy.Outcome outcome;
                    long startedNanos = System.nanoTime();
                    try {
                        outcome = connectAndRead();
                    } catch (RuntimeException e) {
                        LOGGER.warn("Event stream of {} failed: {}", serverUrl, e.getMessage());
                        outcome = ReconnectPolicy.Outcome.FAILED;
                    }
                    if (isCancelled()) {
                        break;
                    }
                    ReconnectPolicy.Decision decision = policy.next(outcome, eventsSeen, System.nanoTime() - startedNanos);
                    eventsSeen = false;
                    if (decision.outcome() == ReconnectPolicy.Outcome.ROLLOVER) {
                        continue;
                    }
                    reportLink(false);
                    if (decision.outcome() == ReconnectPolicy.Outcome.REJECTED) {
                        rejected = true;
                        reportRejected();
                        break;
                    }
                    if (pause(decision.delay())) {
                        break;
                    }
                }
            } finally {
                closeBody();
            }
        }

        private ReconnectPolicy.Outcome connectAndRead() {
            Optional<String> token = stateService.tokenFor(serverUrl);
            if (token.isEmpty()) {
                return ReconnectPolicy.Outcome.REJECTED;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + Endpoints.SERVER_EVENTS.getPath()))
                .timeout(timing.headerTimeout())
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token.get())
                .GET()
                .build();
            CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            pending = future;
            if (isCancelled()) {
                future.cancel(true);
                return ReconnectPolicy.Outcome.FAILED;
            }
            HttpResponse<InputStream> response;
            try {
                response = future.get();
            } catch (InterruptedException | CancellationException e) {
                return ReconnectPolicy.Outcome.FAILED;
            } catch (ExecutionException e) {
                LOGGER.debug("Could not open event stream of {}: {}", serverUrl, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                return ReconnectPolicy.Outcome.FAILED;
            }
            body = response.body();
            if (isCancelled()) {
                closeBody();
                return ReconnectPolicy.Outcome.FAILED;
            }
            int status = response.statusCode();
            if (status == 401) {
                closeBody();
                LOGGER.warn("{} rejected this client's token, the event stream stays closed until the client is paired again", serverUrl);
                return ReconnectPolicy.Outcome.REJECTED;
            }
            if (status / 100 == 3) {
                closeBody();
                LOGGER.warn("Event stream of {} was answered with a redirect to {}, the client does not follow redirects, use that address instead",
                    serverUrl, response.headers().firstValue("Location").orElse("an unknown location"));
                return ReconnectPolicy.Outcome.FAILED;
            }
            if (status == 404) {
                closeBody();
                LOGGER.debug("{} has no event stream, falling back to polling and retrying in {}", serverUrl, timing.unsupportedRetry());
                return ReconnectPolicy.Outcome.UNSUPPORTED;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (status / 100 != 2 || !contentType.toLowerCase().startsWith("text/event-stream")) {
                closeBody();
                LOGGER.debug("Event stream of {} answered {} {}", serverUrl, status, contentType);
                return ReconnectPolicy.Outcome.FAILED;
            }
            return read(response.body());
        }

        private ReconnectPolicy.Outcome read(InputStream input) {
            lastActivityNanos = System.nanoTime();
            ScheduledFuture<?> watchdog = scheduler.scheduleWithFixedDelay(this::checkWatchdog, checkPeriodMillis(), checkPeriodMillis(), TimeUnit.MILLISECONDS);
            try {
                boolean sawEvent = SseReader.read(input, () -> lastActivityNanos = System.nanoTime(), (name, data) -> {
                    if (!eventsSeen) {
                        eventsSeen = true;
                        reportLink(true);
                    }
                    dispatch(name, data);
                });
                return sawEvent ? ReconnectPolicy.Outcome.ROLLOVER : ReconnectPolicy.Outcome.FAILED;
            } catch (IOException | RuntimeException e) {
                if (!isCancelled()) {
                    LOGGER.debug("Event stream of {} ended: {}", serverUrl, e.getMessage());
                }
                return ReconnectPolicy.Outcome.FAILED;
            } finally {
                watchdog.cancel(false);
            }
        }

        private long checkPeriodMillis() {
            return Math.max(10, timing.watchdog().toMillis() / 4);
        }

        private void checkWatchdog() {
            if (System.nanoTime() - lastActivityNanos > timing.watchdog().toNanos()) {
                LOGGER.warn("Event stream of {} went quiet for {}, reconnecting", serverUrl, timing.watchdog());
                closeBody();
            }
        }

        private void reportLink(boolean connected) {
            if (linkUp == connected || isCancelled()) {
                return;
            }
            linkUp = connected;
            synchronized (lock) {
                if (current == this) {
                    if (connected) {
                        dashboard.streamUp(serverUrl);
                    } else {
                        dashboard.streamDown(serverUrl);
                    }
                }
            }
        }

        private void reportRejected() {
            synchronized (lock) {
                if (current == this) {
                    dashboard.pairingRejected(serverUrl);
                }
            }
        }

        private void dispatch(String name, String data) {
            if (!FORWARDED_EVENTS.contains(name)) {
                return;
            }
            try {
                JsonNode node = mapper.readTree(data);
                if (name.equals("state") && !node.has("running")) {
                    return;
                }
                String forwarded = mapper.writeValueAsString(node);
                boolean running = !name.equals("run-finished") && node.path("running").asBoolean(false);
                synchronized (lock) {
                    if (current != this) {
                        return;
                    }
                    switch (name) {
                        case "backups-changed" -> dashboard.backupsChanged(serverUrl, forwarded);
                        case "config-changed" -> dashboard.configChanged(serverUrl);
                        default -> dashboard.runEvent(serverUrl, name, forwarded, running);
                    }
                }
            } catch (JacksonException e) {
                LOGGER.debug("Ignoring malformed {} event from {}", name, serverUrl);
            }
        }
    }

    private final ClientStateService stateService;
    private final DashboardService dashboard;
    private final ObjectMapper mapper;
    private final Timing timing;
    private final HttpClient httpClient;
    private final ThreadFactory threadFactory;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final Object lock = new Object();
    private Subscription current;
    private long lastRecoveryRestartNanos;
    private boolean recoveryRestarted;
    private ScheduledFuture<?> deferredRecovery;

    @Autowired
    public ServerEventStreamService(ClientStateService stateService, DashboardService dashboard, ObjectMapper mapper, ServerTls tls) {
        this(stateService, dashboard, mapper, tls, new Timing(HEADER_TIMEOUT, WATCHDOG, INITIAL_BACKOFF, MAX_BACKOFF, UNSUPPORTED_RETRY, MIN_STREAM_LIFETIME));
    }

    ServerEventStreamService(ClientStateService stateService, DashboardService dashboard, ObjectMapper mapper, ServerTls tls, Timing timing) {
        this(stateService, dashboard, mapper, tls, timing, System::nanoTime, Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("server-event-watchdog").unstarted(runnable)));
    }

    ServerEventStreamService(ClientStateService stateService, DashboardService dashboard, ObjectMapper mapper, ServerTls tls, Timing timing,
                             LongSupplier nanoTime, ScheduledExecutorService scheduler) {
        this.nanoTime = nanoTime;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(tls.context())
            .build();
        this.stateService = stateService;
        this.dashboard = dashboard;
        this.mapper = mapper;
        this.timing = timing;
        AtomicInteger counter = new AtomicInteger();
        this.threadFactory = runnable -> Thread.ofPlatform().daemon().name("server-event-stream-" + counter.incrementAndGet()).unstarted(runnable);
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        stateService.addActiveServerListener(ignored -> reconcile());
        stateService.addPairingListener(this::pairingChanged);
        dashboard.addRecoveryListener(this::recovered);
        reconcile();
    }

    void pairingChanged(String serverUrl) {
        synchronized (lock) {
            if (current != null && Objects.equals(current.serverUrl, serverUrl)) {
                dropCurrent();
            }
            reconcile();
        }
    }

    void recovered(String serverUrl) {
        synchronized (lock) {
            if (current != null && Objects.equals(current.serverUrl, serverUrl)) {
                recover(current);
            }
        }
    }

    private void recover(Subscription stream) {
        synchronized (lock) {
            if (current != stream || !stream.rejected) {
                return;
            }
            long now = nanoTime.getAsLong();
            if (recoveryRestarted && now - lastRecoveryRestartNanos < timing.unsupportedRetry().toNanos()) {
                long remaining = timing.unsupportedRetry().toNanos() - (now - lastRecoveryRestartNanos);
                cancelDeferredRecovery();
                try {
                    deferredRecovery = scheduler.schedule(() -> recover(stream), remaining, TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException ignored) {
                    deferredRecovery = null;
                }
                return;
            }
            recoveryRestarted = true;
            lastRecoveryRestartNanos = now;
            dropCurrent();
            reconcile();
        }
    }

    private void dropCurrent() {
        cancelDeferredRecovery();
        if (current != null) {
            current.cancel();
            current = null;
        }
    }

    private void cancelDeferredRecovery() {
        if (deferredRecovery != null) {
            deferredRecovery.cancel(false);
            deferredRecovery = null;
        }
    }

    void reconcile() {
        synchronized (lock) {
            String active = ServerUrls.nullIfBlank(stateService.getActiveServer());
            String target = active != null && stateService.hasPairing(active) ? active : null;
            String running = current == null ? null : current.serverUrl;
            if (Objects.equals(target, running)) {
                return;
            }
            dropCurrent();
            dashboard.sync();
            if (target != null) {
                Subscription stream = new Subscription(target);
                stream.thread = threadFactory.newThread(stream);
                current = stream;
                stream.thread.start();
                LOGGER.debug("Subscribing to live events of {}", target);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            dropCurrent();
        }
        scheduler.shutdownNow();
        httpClient.shutdownNow();
    }
}
