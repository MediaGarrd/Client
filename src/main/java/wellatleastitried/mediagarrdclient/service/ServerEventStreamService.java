package wellatleastitried.mediagarrdclient.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.api.Endpoints;

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

    private enum Outcome {
        ROLLOVER,
        UNSUPPORTED,
        FAILED
    }

    private final class Stream implements Runnable {

        private final String serverUrl;
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private volatile CompletableFuture<HttpResponse<InputStream>> pending;
        private volatile InputStream body;
        private volatile long lastActivityNanos;
        private boolean linkUp;
        private boolean eventsSeen;
        private Thread thread;

        private Stream(String serverUrl) {
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
            Duration backoff = timing.initialBackoff();
            try {
                while (!isCancelled()) {
                    Outcome outcome;
                    long startedNanos = System.nanoTime();
                    try {
                        outcome = connectAndRead();
                    } catch (RuntimeException e) {
                        LOGGER.warn("Event stream of {} failed: {}", serverUrl, e.getMessage());
                        outcome = Outcome.FAILED;
                    }
                    if (isCancelled()) {
                        break;
                    }
                    boolean shortLived = System.nanoTime() - startedNanos < timing.minStreamLifetime().toNanos();
                    if (eventsSeen) {
                        eventsSeen = false;
                        if (!shortLived) {
                            backoff = timing.initialBackoff();
                        }
                    }
                    if (outcome == Outcome.ROLLOVER && shortLived) {
                        outcome = Outcome.FAILED;
                    }
                    if (outcome == Outcome.ROLLOVER) {
                        continue;
                    }
                    reportLink(false);
                    Duration delay = outcome == Outcome.UNSUPPORTED ? timing.unsupportedRetry() : backoff;
                    if (outcome == Outcome.FAILED) {
                        backoff = min(backoff.multipliedBy(2), timing.maxBackoff());
                    }
                    if (pause(delay)) {
                        break;
                    }
                }
            } finally {
                closeBody();
            }
        }

        private Outcome connectAndRead() {
            HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + Endpoints.SERVER_EVENTS.getPath()))
                .timeout(timing.headerTimeout())
                .header("Accept", "text/event-stream")
                .GET()
                .build();
            CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            pending = future;
            if (isCancelled()) {
                future.cancel(true);
                return Outcome.FAILED;
            }
            HttpResponse<InputStream> response;
            try {
                response = future.get();
            } catch (InterruptedException | CancellationException e) {
                return Outcome.FAILED;
            } catch (ExecutionException e) {
                LOGGER.debug("Could not open event stream of {}: {}", serverUrl, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                return Outcome.FAILED;
            }
            body = response.body();
            if (isCancelled()) {
                closeBody();
                return Outcome.FAILED;
            }
            int status = response.statusCode();
            if (status == 404) {
                closeBody();
                LOGGER.info("{} has no event stream, falling back to polling and retrying in {}", serverUrl, timing.unsupportedRetry());
                return Outcome.UNSUPPORTED;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (status / 100 != 2 || !contentType.toLowerCase().startsWith("text/event-stream")) {
                closeBody();
                LOGGER.debug("Event stream of {} answered {} {}", serverUrl, status, contentType);
                return Outcome.FAILED;
            }
            return read(response.body());
        }

        private Outcome read(InputStream input) {
            lastActivityNanos = System.nanoTime();
            ScheduledFuture<?> watchdog = scheduler.scheduleWithFixedDelay(this::checkWatchdog, checkPeriodMillis(), checkPeriodMillis(), TimeUnit.MILLISECONDS);
            boolean sawEvent = false;
            String name = null;
            StringBuilder data = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastActivityNanos = System.nanoTime();
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            if (!sawEvent) {
                                sawEvent = true;
                                eventsSeen = true;
                                reportLink(true);
                            }
                            dispatch(name == null ? "message" : name, data.toString());
                        }
                        name = null;
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        continue;
                    } else {
                        int colon = line.indexOf(':');
                        String field = colon < 0 ? line : line.substring(0, colon);
                        String value = colon < 0 ? "" : line.substring(colon + 1);
                        if (value.startsWith(" ")) {
                            value = value.substring(1);
                        }
                        if (field.equals("event")) {
                            name = value;
                        } else if (field.equals("data")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(value);
                        }
                    }
                }
                return sawEvent ? Outcome.ROLLOVER : Outcome.FAILED;
            } catch (IOException | RuntimeException e) {
                if (!isCancelled()) {
                    LOGGER.debug("Event stream of {} ended: {}", serverUrl, e.getMessage());
                }
                return Outcome.FAILED;
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

        private void dispatch(String name, String data) {
            if (!FORWARDED_EVENTS.contains(name)) {
                return;
            }
            try {
                JsonNode node = mapper.readTree(data);
                if (name.equals("state") && !node.has("running")) {
                    return;
                }
                boolean running = !name.equals("run-finished") && node.path("running").asBoolean(false);
                synchronized (lock) {
                    if (current != this) {
                        return;
                    }
                    switch (name) {
                        case "backups-changed" -> dashboard.backupsChanged(serverUrl, data);
                        case "config-changed" -> dashboard.configChanged(serverUrl);
                        default -> dashboard.runEvent(serverUrl, name, data, running);
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("Ignoring malformed {} event from {}", name, serverUrl);
            }
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private final ClientStateService stateService;
    private final DashboardService dashboard;
    private final ObjectMapper mapper;
    private final Timing timing;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
    private final ThreadFactory threadFactory;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private Stream current;

    @Autowired
    public ServerEventStreamService(ClientStateService stateService, DashboardService dashboard, ObjectMapper mapper) {
        this(stateService, dashboard, mapper, new Timing(HEADER_TIMEOUT, WATCHDOG, INITIAL_BACKOFF, MAX_BACKOFF, UNSUPPORTED_RETRY, MIN_STREAM_LIFETIME));
    }

    ServerEventStreamService(ClientStateService stateService, DashboardService dashboard, ObjectMapper mapper, Timing timing) {
        this.stateService = stateService;
        this.dashboard = dashboard;
        this.mapper = mapper;
        this.timing = timing;
        AtomicInteger counter = new AtomicInteger();
        this.threadFactory = runnable -> Thread.ofPlatform().daemon().name("server-event-stream-" + counter.incrementAndGet()).unstarted(runnable);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("server-event-watchdog").unstarted(runnable));
    }

    @PostConstruct
    void start() {
        stateService.addActiveServerListener(ignored -> reconcile());
        reconcile();
    }

    void reconcile() {
        synchronized (lock) {
            String target = normalize(stateService.getActiveServer());
            String running = current == null ? null : current.serverUrl;
            if (Objects.equals(target, running)) {
                return;
            }
            if (current != null) {
                current.cancel();
                current = null;
            }
            dashboard.sync();
            if (target != null) {
                Stream stream = new Stream(target);
                stream.thread = threadFactory.newThread(stream);
                current = stream;
                stream.thread.start();
                LOGGER.info("Subscribing to live events of {}", target);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            if (current != null) {
                current.cancel();
                current = null;
            }
        }
        scheduler.shutdownNow();
        httpClient.shutdownNow();
    }

    private static String normalize(String url) {
        return url == null || url.isBlank() ? null : url;
    }
}
