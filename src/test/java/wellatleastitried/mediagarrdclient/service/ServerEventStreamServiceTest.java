package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.service.ServerEventStreamService.Timing;

@Timeout(30)
class ServerEventStreamServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .build();
    private static final Timing FAST = new Timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMillis(400), Duration.ZERO);

    interface Behavior {
        void handle(HttpExchange exchange, int connection) throws Exception;
    }

    static final class FakeServer {

        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final BlockingQueue<Long> connections = new LinkedBlockingQueue<>();
        final AtomicInteger count = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> authorizations = new CopyOnWriteArrayList<>();

        FakeServer(Behavior behavior) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/v1/events", exchange -> {
                int connection = count.incrementAndGet();
                authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                connections.add(System.nanoTime());
                try {
                    behavior.handle(exchange, connection);
                } catch (Exception e) {
                    exchange.close();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        long awaitConnection() throws InterruptedException {
            Long at = connections.poll(10, TimeUnit.SECONDS);
            assertNotNull(at, "the fake server was never contacted");
            return at;
        }

        void stop() {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @TempDir
    Path tempDir;

    private ClientStateService state;
    private RecordingPublisher publisher;
    private DashboardService dashboard;
    private ServerApiService serverApi;
    private ExecutorService refreshes;
    private ServerEventStreamService service;
    private RecordingPublisher browser;
    private final List<FakeServer> servers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MediaGarrdProperties configuration = new MediaGarrdProperties();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
        state = new ClientStateService(configuration, MAPPER);
        publisher = new RecordingPublisher();
        serverApi = mock(ServerApiService.class);
        when(serverApi.health(anyString())).thenThrow(new ResourceAccessException("refused"));
        when(serverApi.serverVersion(anyString())).thenThrow(new ResourceAccessException("refused"));
        UpdateCheckService updateCheck = mock(UpdateCheckService.class);
        when(updateCheck.status()).thenReturn(new VersionStatusDto("0.1.0", null, null, false, CheckState.NOT_CHECKED, null, null));
        refreshes = Executors.newSingleThreadExecutor();
        dashboard = new DashboardService(state, publisher, serverApi, new VersionService(updateCheck, serverApi), updateCheck, MAPPER, refreshes, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        refreshes.shutdownNow();
        servers.forEach(FakeServer::stop);
    }

    private void activate(String url) {
        state.storePairing(url, new StoredPairing("test-token", "abc", "Test"));
        state.setActiveServer(url);
    }

    private FakeServer fake(Behavior behavior) throws IOException {
        FakeServer fake = new FakeServer(behavior);
        servers.add(fake);
        return fake;
    }

    private void startService(Timing timing) {
        service = new ServerEventStreamService(state, dashboard, MAPPER, ServerTls.defaults(), timing);
        browser = dashboard.subscribe(publisher::subscribe).orElseThrow();
        service.start();
    }

    private static void frame(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static OutputStream open(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        frame(out, ": connected\n\n");
        return out;
    }

    private static Behavior holdOpen(CountDownLatch closed, String... frames) {
        return (exchange, connection) -> {
            OutputStream out = open(exchange);
            for (String text : frames) {
                frame(out, text);
            }
            try {
                while (true) {
                    Thread.sleep(10);
                    frame(out, ": keep\n\n");
                }
            } catch (IOException e) {
                closed.countDown();
            }
        };
    }

    private List<String> collectUntil(String marker) throws InterruptedException {
        List<String> seen = new ArrayList<>();
        while (true) {
            seen.add(browser.next());
            if (seen.get(seen.size() - 1).contains(marker)) {
                return seen;
            }
        }
    }

    @Test
    void forwardsRunEventsAndIgnoresEverythingElse() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event:state\ndata:{\"running\":false,\"at\":\"t0\"}\n\n");
            frame(out, "event: ping\ndata: {}\n\n");
            frame(out, "event: surprise\ndata: {\"x\":1}\n\n");
            frame(out, "event: run-started\ndata: not-json\n\n");
            frame(out, "event: run-started\r\ndata: {\"running\":true,\"startedAt\":\"t1\"}\r\n\r\n");
            frame(out, "event: backups-changed\ndata: not-json\n\n");
            frame(out, "event: backups-changed\ndata: {\"at\":\"t3\"}\n\n");
            frame(out, "event: config-changed\ndata: {\"at\":\"t4\"}\n\n");
            frame(out, "event:run-finished\ndata:{\"running\":false,\"status\":\"PARTIAL\",\"startedAt\":\"t1\",\"finishedAt\":\"t2\"}\n\n");
            hold();
        });
        activate(fake.url());

        startService(FAST);

        List<String> seen = collectUntil("event:run-finished");
        List<String> relevant = seen.stream().filter(event -> !nameOf(event).equals("versions") && !isLink(event, "DOWN")).toList();
        List<String> names = relevant.stream().map(ServerEventStreamServiceTest::nameOf).toList();
        assertEquals(List.of("snapshot", "snapshot", "link", "state", "run-started", "backups-changed", "run-finished"), names);
        assertTrue(dataOf(relevant.get(0)).path("activeServer").isNull());
        assertEquals(fake.url(), dataOf(relevant.get(1)).path("activeServer").asString());
        assertEquals("CONNECTING", dataOf(relevant.get(1)).path("link").asString());
        assertEquals("LIVE", dataOf(relevant.get(2)).path("state").asString());
        assertEquals(fake.url(), dataOf(relevant.get(2)).path("server").asString());
        assertEquals("t1", dataOf(relevant.get(4)).path("startedAt").asString());
        assertEquals("t3", dataOf(relevant.get(5)).path("at").asString());
        assertEquals("PARTIAL", dataOf(relevant.get(6)).path("status").asString());
    }

    @Test
    void switchingBetweenTwoUnpairedKnownServersUpdatesTheDashboard() throws InterruptedException {
        dashboard.start();
        state.updateKnownServers(List.of("http://unpaired-a:38471", "http://unpaired-b:38471"));
        startService(FAST);
        assertEquals("http://unpaired-a:38471", dashboard.state().activeServer());

        state.setActiveServer("http://unpaired-b:38471");

        assertEquals("http://unpaired-b:38471", dashboard.state().activeServer());
        String snapshot = browser.nextContaining("\"activeServer\":\"http://unpaired-b:38471\"");
        assertEquals("snapshot", nameOf(snapshot));
    }

    @Test
    void forwardsEventDataAsOneLineSoItCannotInjectFields() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event: backups-changed\ndata: {\"at\":\ndata: \"t3\",\ndata: \"x\":\"y\"}\n\n");
            frame(out, "event:run-finished\ndata:{\"running\":false,\"status\":\"OK\"}\n\n");
            hold();
        });
        activate(fake.url());

        startService(FAST);

        List<String> seen = collectUntil("event:run-finished");
        String changed = seen.stream().filter(event -> nameOf(event).equals("backups-changed")).findFirst().orElseThrow();
        assertEquals(List.of("event:backups-changed", "data:{\"at\":\"t3\",\"x\":\"y\"}"), changed.lines().filter(line -> !line.isEmpty()).toList());
    }

    private static String nameOf(String event) {
        return RecordingPublisher.eventName(event);
    }

    private static JsonNode dataOf(String event) {
        return RecordingPublisher.data(event);
    }

    private static boolean isLink(String event, String state) {
        return nameOf(event).equals("link") && state.equals(dataOf(event).path("state").asString());
    }

    private String awaitLink(String state) throws InterruptedException {
        while (true) {
            String event = browser.next();
            if (isLink(event, state)) {
                return event;
            }
        }
    }

    private static List<Thread> streamThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().startsWith("server-event-stream-"))
            .toList();
    }

    private static void hold() throws Exception {
        new CountDownLatch(1).await(20, TimeUnit.SECONDS);
    }

    @Test
    void reconnectsRightAwayAfterACleanEndWithoutFlickeringTheLink() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":" + (connection == 1) + "}\n\n");
            if (connection > 1) {
                hold();
            }
        });
        activate(fake.url());

        startService(FAST);

        fake.awaitConnection();
        fake.awaitConnection();
        List<String> seen = collectUntil("\"running\":false");
        int connectedAt = -1;
        for (int i = 0; i < seen.size(); i++) {
            if (isLink(seen.get(i), "LIVE")) {
                assertEquals(-1, connectedAt, "link reported connected twice");
                connectedAt = i;
            }
        }
        assertTrue(connectedAt >= 0);
        assertTrue(seen.subList(connectedAt, seen.size()).stream().noneMatch(event -> isLink(event, "DOWN")));
    }

    @Test
    void retriesWithBackoffAfterAFailureAndReportsTheLinkDown() throws Exception {
        CountDownLatch allowStream = new CountDownLatch(1);
        FakeServer fake = fake((exchange, connection) -> {
            if (connection < 3) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            allowStream.await(10, TimeUnit.SECONDS);
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":true}\n\n");
            hold();
        });
        activate(fake.url());

        startService(FAST);

        long first = fake.awaitConnection();
        long second = fake.awaitConnection();
        long third = fake.awaitConnection();
        awaitLink("DOWN");
        allowStream.countDown();
        List<String> seen = collectUntil("event:state");
        assertEquals(3, fake.count.get());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(second - first) >= 20, "first retry came before the initial backoff");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(third - second) >= 40, "second retry came before the doubled backoff");
        assertTrue(seen.stream().anyMatch(event -> isLink(event, "LIVE")));
    }

    @Test
    void rejectsAResponseThatIsNotAnEventStream() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            if (connection == 1) {
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, 0);
                frame(exchange.getResponseBody(), "event: state\ndata: {\"running\":true}\n\n");
                return;
            }
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":false}\n\n");
            hold();
        });
        activate(fake.url());

        startService(FAST);

        List<String> seen = collectUntil("event:state");
        assertFalse(dataOf(seen.get(seen.size() - 1)).path("running").asBoolean(true));
        assertEquals(2, fake.count.get());
    }

    @Test
    void anOldServerWithoutTheStreamIsRetriedOnlyAfterTheLongInterval() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            if (connection == 1) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":false}\n\n");
            hold();
        });
        activate(fake.url());

        startService(FAST);

        long first = fake.awaitConnection();
        long second = fake.awaitConnection();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(second - first) >= 350, "retried too early");
        collectUntil("event:state");
    }

    @Test
    void movingToAnotherServerClosesTheOldStreamAndSubscribesToTheNewOne() throws Exception {
        CountDownLatch oldClosed = new CountDownLatch(1);
        FakeServer old = fake(holdOpen(oldClosed, "event: state\ndata: {\"running\":true}\n\n"));
        FakeServer next = fake(holdOpen(new CountDownLatch(1), "event: state\ndata: {\"running\":false}\n\n"));
        activate(old.url());
        startService(FAST);
        collectUntil("\"running\":true");

        activate(next.url());

        assertTrue(oldClosed.await(10, TimeUnit.SECONDS), "the old stream was not closed");
        List<String> seen = collectUntil("\"running\":false");
        assertTrue(seen.stream().anyMatch(event -> nameOf(event).equals("snapshot")
            && next.url().equals(dataOf(event).path("activeServer").asString()) && dataOf(event).path("running").isNull()));
        assertTrue(seen.stream().anyMatch(event -> isLink(event, "LIVE") && next.url().equals(dataOf(event).path("server").asString())));
        assertEquals(1, old.count.get());
    }

    @Test
    void clearingTheActiveServerStopsTheStreamAndDoesNotReconnect() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        FakeServer fake = fake(holdOpen(closed, "event: state\ndata: {\"running\":true}\n\n"));
        activate(fake.url());
        startService(FAST);
        collectUntil("\"running\":true");

        state.setActiveServer("");

        assertTrue(closed.await(10, TimeUnit.SECONDS));
        String cleared = browser.nextContaining("\"activeServer\":null");
        assertEquals("snapshot", nameOf(cleared));
        assertEquals("DOWN", dataOf(cleared).path("link").asString(), cleared);
        assertEquals(1, fake.count.get());
    }

    @Test
    void doesNotConnectWithoutAnActiveServer() throws Exception {
        startService(FAST);

        String snapshot = browser.next();
        assertEquals("snapshot", nameOf(snapshot));
        assertEquals("DOWN", dataOf(snapshot).path("link").asString());
        assertTrue(streamThreads().isEmpty());
        assertNull(browser.sent.poll());
    }

    @Test
    void reconnectsAStreamThatGoesSilent() throws Exception {
        Timing quick = new Timing(Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMillis(400), Duration.ZERO);
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":" + (connection == 1) + "}\n\n");
            hold();
        });
        activate(fake.url());

        startService(quick);

        fake.awaitConnection();
        fake.awaitConnection();
        List<String> seen = collectUntil("\"running\":false");
        List<String> linkStates = seen.stream().filter(event -> nameOf(event).equals("link")).map(event -> dataOf(event).path("state").asString()).toList();
        int firstLive = linkStates.indexOf("LIVE");
        assertTrue(firstLive >= 0, "the first connection never went live: " + linkStates);
        List<String> afterwards = linkStates.subList(firstLive + 1, linkStates.size());
        assertEquals("LIVE", afterwards.getLast(), "the link did not come back after the silence: " + linkStates);
        assertTrue(afterwards.stream().anyMatch(state -> !state.equals("LIVE")), "the silent stream was never reported as lost: " + linkStates);
        assertEquals(2, fake.count.get());
    }

    @Test
    void startsOnItsOwnForAServerRestoredFromTheStateFile() throws Exception {
        FakeServer fake = fake(holdOpen(new CountDownLatch(1), "event: state\ndata: {\"running\":true}\n\n"));
        activate(fake.url());
        MediaGarrdProperties configuration = new MediaGarrdProperties();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
        state = new ClientStateService(configuration, MAPPER);

        startService(FAST);

        fake.awaitConnection();
        collectUntil("\"running\":true");
    }

    @Test
    void aStreamThatEndsRightAfterOpeningIsRetriedWithBackoffInsteadOfInALoop() throws Exception {
        Timing slowRetry = new Timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(400), Duration.ofMinutes(1));
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":false}\n\n");
        });
        activate(fake.url());

        startService(slowRetry);

        long first = fake.awaitConnection();
        long second = fake.awaitConnection();
        long third = fake.awaitConnection();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(second - first) >= 250, "reconnected too fast");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(third - second) >= 250, "reconnected too fast");
    }

    @Test
    void severalConfigChangedEventsDuringARefreshAreCoalescedIntoOneMoreFetch() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch firstFetchStarted = new CountDownLatch(1);
        CountDownLatch finishFetch = new CountDownLatch(1);
        doAnswer(invocation -> {
            fetches.incrementAndGet();
            firstFetchStarted.countDown();
            finishFetch.await(10, TimeUnit.SECONDS);
            return new ServerStatusDto(true, false, 5, Duration.ofHours(6));
        }).when(serverApi).health(anyString());
        CountDownLatch burst = new CountDownLatch(1);
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":false}\n\n");
            burst.await(10, TimeUnit.SECONDS);
            for (int i = 0; i < 3; i++) {
                frame(out, "event: config-changed\ndata: {\"at\":\"t" + i + "\"}\n\n");
            }
            frame(out, "event: state\ndata: {\"running\":true}\n\n");
            hold();
        });
        activate(fake.url());

        startService(FAST);

        assertTrue(firstFetchStarted.await(10, TimeUnit.SECONDS));
        awaitLink("LIVE");
        burst.countDown();
        browser.nextContaining("\"running\":true");
        assertEquals(1, fetches.get(), "no fetch may start while the first one is still in flight");
        finishFetch.countDown();
        refreshes.submit(() -> { }).get(10, TimeUnit.SECONDS);

        assertEquals(2, fetches.get(), "the burst of config-changed events must cost exactly one more fetch");
    }

    @Test
    void sendsTheBearerTokenWhenSubscribing() throws Exception {
        FakeServer fake = fake(holdOpen(new CountDownLatch(1), "event: state\ndata: {\"running\":true}\n\n"));
        activate(fake.url());

        startService(FAST);

        fake.awaitConnection();
        assertEquals("Bearer test-token", fake.authorizations.getFirst());
    }

    @Test
    void aRejectedTokenStopsTheStreamInsteadOfRetrying() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            exchange.sendResponseHeaders(401, -1);
        });
        activate(fake.url());

        startService(FAST);

        fake.awaitConnection();
        awaitRejected();
        assertEquals(1, fake.count.get());
        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.REJECTED, dashboard.state().pairing());
    }

    @Test
    void pairingAgainRestartsTheStreamWithTheNewToken() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            if ("Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":true}\n\n");
            hold();
        });
        activate(fake.url());
        startService(FAST);
        fake.awaitConnection();

        state.storePairing(fake.url(), new StoredPairing("second-token", "id2", "Test"));

        fake.awaitConnection();
        collectUntil("\"running\":true");
        assertEquals("Bearer second-token", fake.authorizations.getLast());
    }

    @Test
    void doesNotConnectToAServerThatIsNotPaired() throws Exception {
        FakeServer fake = fake(holdOpen(new CountDownLatch(1)));
        state.setActiveServer(fake.url());

        startService(FAST);

        assertEquals("snapshot", nameOf(browser.next()));
        assertTrue(streamThreads().isEmpty());
        assertEquals(0, fake.count.get());
    }

    @Test
    void doesNotFollowARedirectWithTheToken() throws Exception {
        FakeServer elsewhere = fake(holdOpen(new CountDownLatch(1)));
        FakeServer fake = fake((exchange, connection) -> {
            exchange.getResponseHeaders().add("Location", elsewhere.url() + "/api/v1/events");
            exchange.sendResponseHeaders(302, -1);
        });
        activate(fake.url());

        startService(FAST);

        fake.awaitConnection();
        fake.awaitConnection();
        assertEquals(0, elsewhere.count.get());
    }

    @Test
    void recoveryRestartsARejectedStreamAgainOnceTheCoolDownHasPassed() throws Exception {
        FakeServer fake = fake((exchange, connection) -> exchange.sendResponseHeaders(401, -1));
        activate(fake.url());
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        CapturingScheduler scheduler = new CapturingScheduler();
        startService(new Timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMinutes(5), Duration.ZERO), nanos, scheduler);
        fake.awaitConnection();
        awaitRejected();

        service.recovered(fake.url());
        fake.awaitConnection();
        awaitRejected();
        nanos.addAndGet(Duration.ofMinutes(5).toNanos());
        service.recovered(fake.url());

        fake.awaitConnection();
        assertEquals(3, fake.count.get());
        assertTrue(scheduler.commands.isEmpty(), "a recovery after the cool-down must restart at once, not be deferred");
        scheduler.shutdownNow();
    }

    static final class CapturingScheduler extends ScheduledThreadPoolExecutor {

        final List<Runnable> commands = new CopyOnWriteArrayList<>();
        final List<Long> delays = new CopyOnWriteArrayList<>();
        final List<ScheduledFuture<?>> futures = new CopyOnWriteArrayList<>();

        CapturingScheduler() {
            super(1, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            commands.add(command);
            delays.add(unit.toNanos(delay));
            ScheduledFuture<?> future = super.schedule(command, 1, TimeUnit.DAYS);
            futures.add(future);
            return future;
        }
    }

    private void startService(Timing timing, AtomicLong nanos, CapturingScheduler scheduler) {
        service = new ServerEventStreamService(state, dashboard, MAPPER, ServerTls.defaults(), timing, nanos::get, scheduler);
        browser = dashboard.subscribe(publisher::subscribe).orElseThrow();
        service.start();
    }

    @Test
    void recoveryDuringTheCoolDownIsDeferredAndRestartsAStillRejectedStream() throws Exception {
        FakeServer fake = fake((exchange, connection) -> exchange.sendResponseHeaders(401, -1));
        activate(fake.url());
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        CapturingScheduler scheduler = new CapturingScheduler();
        startService(new Timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMinutes(5), Duration.ZERO), nanos, scheduler);
        fake.awaitConnection();
        awaitRejected();

        service.recovered(fake.url());
        fake.awaitConnection();
        awaitRejected();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(10));
        service.recovered(fake.url());
        service.recovered(fake.url());

        assertEquals(2, fake.count.get());
        assertEquals(2, scheduler.commands.size());
        assertEquals(Duration.ofMinutes(5).minusSeconds(10).toNanos(), scheduler.delays.getLast());
        assertTrue(scheduler.futures.getFirst().isCancelled());

        nanos.addAndGet(Duration.ofMinutes(5).toNanos());
        scheduler.commands.getLast().run();
        fake.awaitConnection();
        assertEquals(3, fake.count.get());
        scheduler.shutdownNow();
    }

    @Test
    void aDeferredRecoveryIsCancelledAndIgnoredOnceTheStreamWasReplaced() throws Exception {
        FakeServer fake = fake((exchange, connection) -> exchange.sendResponseHeaders(401, -1));
        activate(fake.url());
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        CapturingScheduler scheduler = new CapturingScheduler();
        startService(new Timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMinutes(5), Duration.ZERO), nanos, scheduler);
        fake.awaitConnection();
        awaitRejected();
        service.recovered(fake.url());
        fake.awaitConnection();
        awaitRejected();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(10));
        service.recovered(fake.url());
        assertEquals(1, scheduler.commands.size());

        state.storePairing(fake.url(), new StoredPairing("new-token", "def", "Test"));
        fake.awaitConnection();
        awaitRejected();

        assertTrue(scheduler.futures.getFirst().isCancelled());
        nanos.addAndGet(Duration.ofMinutes(10).toNanos());
        scheduler.commands.getFirst().run();
        assertNull(fake.connections.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(3, fake.count.get());
        scheduler.shutdownNow();
    }

    @Test
    void deferredRecoveryDoesNothingOnceTheStreamIsNoLongerRejected() throws Exception {
        FakeServer fake = fake((exchange, connection) -> exchange.sendResponseHeaders(401, -1));
        activate(fake.url());
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        CapturingScheduler scheduler = new CapturingScheduler();
        startService(new Timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMinutes(5), Duration.ZERO), nanos, scheduler);
        fake.awaitConnection();
        awaitRejected();
        service.recovered(fake.url());
        fake.awaitConnection();
        awaitRejected();
        service.recovered(fake.url());
        assertEquals(1, scheduler.commands.size());

        state.setActiveServer(null);

        assertTrue(scheduler.futures.getFirst().isCancelled());
        nanos.addAndGet(Duration.ofMinutes(10).toNanos());
        scheduler.commands.getFirst().run();
        assertTrue(streamThreads().isEmpty());
        assertEquals(2, fake.count.get());
        scheduler.shutdownNow();
    }

    @Test
    void recoveryDoesNotRestartAStreamThatWasNotRejected() throws Exception {
        FakeServer fake = fake(holdOpen(new CountDownLatch(1)));
        activate(fake.url());
        startService(FAST);
        fake.awaitConnection();
        List<Thread> before = streamThreads();
        assertEquals(1, before.size());

        service.recovered(fake.url());

        assertEquals(before, streamThreads(), "a restart would have replaced the stream thread");
        assertEquals(1, fake.count.get());
    }

    @Test
    void recoveryOfAnotherServerDoesNotRestartARejectedStream() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            if (connection == 1) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            holdOpen(new CountDownLatch(1)).handle(exchange, connection);
        });
        activate(fake.url());
        startService(FAST);
        fake.awaitConnection();
        awaitRejected();

        service.recovered("http://127.0.0.1:1");

        assertTrue(streamThreads().isEmpty(), "a restart would have started a stream that stays open");
        assertEquals(1, fake.count.get());
    }

    private void awaitRejected() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!streamThreads().isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the rejected stream never ended");
            Thread.sleep(10);
        }
    }
}
