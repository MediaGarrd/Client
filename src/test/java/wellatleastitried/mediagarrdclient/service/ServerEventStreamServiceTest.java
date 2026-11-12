package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.service.ClientEventBroadcasterTest.RecordingEmitter;
import wellatleastitried.mediagarrdclient.service.ServerEventStreamService.Timing;

@Timeout(30)
class ServerEventStreamServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
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

        FakeServer(Behavior behavior) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/v1/events", exchange -> {
                int connection = count.incrementAndGet();
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
    private ClientEventBroadcaster broadcaster;
    private DashboardService dashboard;
    private ServerApiService serverApi;
    private ExecutorService refreshes;
    private ServerEventStreamService service;
    private RecordingEmitter browser;
    private final List<FakeServer> servers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
        state = new ClientStateService(configuration, MAPPER);
        broadcaster = new ClientEventBroadcaster(Duration.ofMinutes(10), 32, 64, Duration.ofMinutes(5), Duration.ofMinutes(6), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }, RecordingEmitter::new);
        serverApi = mock(ServerApiService.class);
        when(serverApi.health(anyString())).thenThrow(new ResourceAccessException("refused"));
        when(serverApi.serverVersion(anyString())).thenThrow(new ResourceAccessException("refused"));
        UpdateCheckService updateCheck = mock(UpdateCheckService.class);
        when(updateCheck.status()).thenReturn(new VersionStatusDto("0.1.0", null, null, false, CheckState.NOT_CHECKED, null, null));
        refreshes = Executors.newSingleThreadExecutor();
        dashboard = new DashboardService(state, broadcaster, serverApi, new VersionService(updateCheck, serverApi), updateCheck, MAPPER, refreshes, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        broadcaster.shutdown();
        refreshes.shutdownNow();
        servers.forEach(FakeServer::stop);
    }

    private FakeServer fake(Behavior behavior) throws IOException {
        FakeServer fake = new FakeServer(behavior);
        servers.add(fake);
        return fake;
    }

    private void startService(Timing timing) {
        service = new ServerEventStreamService(state, dashboard, MAPPER, timing);
        browser = (RecordingEmitter) dashboard.subscribe().orElseThrow();
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
        state.setActiveServer(fake.url());

        startService(FAST);

        List<String> seen = collectUntil("event:run-finished");
        List<String> relevant = seen.stream().filter(event -> !event.contains("event:versions") && !(event.contains("event:link") && event.contains("\"state\":\"DOWN\""))).toList();
        List<String> names = relevant.stream().map(ServerEventStreamServiceTest::nameOf).toList();
        assertEquals(List.of("snapshot", "snapshot", "link", "state", "run-started", "backups-changed", "run-finished"), names);
        assertTrue(relevant.get(0).contains("\"activeServer\":null"));
        assertTrue(relevant.get(1).contains("\"activeServer\":\"" + fake.url() + "\"") && relevant.get(1).contains("\"link\":\"CONNECTING\""));
        assertTrue(relevant.get(2).contains("\"state\":\"LIVE\"") && relevant.get(2).contains(fake.url()));
        assertTrue(relevant.get(4).contains("\"startedAt\":\"t1\""));
        assertEquals("event:backups-changed\ndata:{\"at\":\"t3\"}\n\n", relevant.get(5));
        assertTrue(relevant.get(6).contains("\"status\":\"PARTIAL\""));
    }

    private static String nameOf(String event) {
        for (String line : event.split("\n")) {
            if (line.startsWith("event:")) {
                return line.substring("event:".length());
            }
        }
        return "";
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
        state.setActiveServer(fake.url());

        startService(FAST);

        fake.awaitConnection();
        fake.awaitConnection();
        List<String> seen = collectUntil("\"running\":false");
        int connectedAt = -1;
        for (int i = 0; i < seen.size(); i++) {
            if (seen.get(i).contains("event:link") && seen.get(i).contains("\"state\":\"LIVE\"")) {
                assertEquals(-1, connectedAt, "link reported connected twice");
                connectedAt = i;
            }
        }
        assertTrue(connectedAt >= 0);
        assertTrue(seen.subList(connectedAt, seen.size()).stream().noneMatch(event -> event.contains("event:link") && event.contains("\"state\":\"DOWN\"")));
    }

    @Test
    void retriesWithBackoffAfterAFailureAndReportsTheLinkDown() throws Exception {
        FakeServer fake = fake((exchange, connection) -> {
            if (connection < 3) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":true}\n\n");
            hold();
        });
        state.setActiveServer(fake.url());

        startService(FAST);

        List<String> seen = collectUntil("event:state");
        assertEquals(3, fake.count.get());
        assertTrue(seen.stream().anyMatch(event -> event.contains("event:link") && event.contains("\"state\":\"LIVE\"")));
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
        state.setActiveServer(fake.url());

        startService(FAST);

        List<String> seen = collectUntil("event:state");
        assertTrue(seen.get(seen.size() - 1).contains("\"running\":false"));
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
        state.setActiveServer(fake.url());

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
        state.setActiveServer(old.url());
        startService(FAST);
        collectUntil("\"running\":true");

        state.setActiveServer(next.url());

        assertTrue(oldClosed.await(10, TimeUnit.SECONDS), "the old stream was not closed");
        List<String> seen = collectUntil("\"running\":false");
        assertTrue(seen.stream().anyMatch(event -> event.contains("event:snapshot") && event.contains("\"activeServer\":\"" + next.url() + "\"") && event.contains("\"running\":null")));
        assertTrue(seen.stream().anyMatch(event -> event.contains("event:link") && event.contains("\"state\":\"LIVE\"") && event.contains(next.url())));
        assertEquals(1, old.count.get());
    }

    @Test
    void clearingTheActiveServerStopsTheStreamAndDoesNotReconnect() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        FakeServer fake = fake(holdOpen(closed, "event: state\ndata: {\"running\":true}\n\n"));
        state.setActiveServer(fake.url());
        startService(FAST);
        collectUntil("\"running\":true");

        state.setActiveServer("");

        assertTrue(closed.await(10, TimeUnit.SECONDS));
        String cleared = browser.nextContaining("\"activeServer\":null");
        assertTrue(cleared.contains("event:snapshot") && cleared.contains("\"link\":\"DOWN\""), cleared);
        assertEquals(1, fake.count.get());
    }

    @Test
    void doesNotConnectWithoutAnActiveServer() throws Exception {
        startService(FAST);

        String snapshot = browser.next();
        assertTrue(snapshot.contains("event:snapshot") && snapshot.contains("\"link\":\"DOWN\""));
        assertNull(browser.sent.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void reconnectsAStreamThatGoesSilent() throws Exception {
        Timing quick = new Timing(Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMillis(400), Duration.ZERO);
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":" + (connection == 1) + "}\n\n");
            hold();
        });
        state.setActiveServer(fake.url());

        startService(quick);

        fake.awaitConnection();
        fake.awaitConnection();
        List<String> seen = collectUntil("\"running\":false");
        assertFalse(seen.isEmpty());
    }

    @Test
    void startsOnItsOwnForAServerRestoredFromTheStateFile() throws Exception {
        FakeServer fake = fake(holdOpen(new CountDownLatch(1), "event: state\ndata: {\"running\":true}\n\n"));
        state.setActiveServer(fake.url());
        Configuration configuration = new Configuration();
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
        state.setActiveServer(fake.url());

        startService(slowRetry);

        long first = fake.awaitConnection();
        long second = fake.awaitConnection();
        long third = fake.awaitConnection();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(second - first) >= 250, "reconnected too fast");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(third - second) >= 250, "reconnected too fast");
    }

    @Test
    void aConfigChangedEventMakesTheClientRefetchTheServerHealthOnce() throws Exception {
        doReturn(new ServerStatusDto(true, false, 5, Duration.ofHours(6))).when(serverApi).health(anyString());
        FakeServer fake = fake((exchange, connection) -> {
            OutputStream out = open(exchange);
            frame(out, "event: state\ndata: {\"running\":false}\n\n");
            Thread.sleep(2000);
            frame(out, "event: config-changed\ndata: {\"at\":\"t\"}\n\n");
            hold();
        });
        state.setActiveServer(fake.url());

        startService(FAST);

        browser.nextContaining("event:config");
        Thread.sleep(800);
        clearInvocations(serverApi);

        verify(serverApi, timeout(10_000).times(1)).health(fake.url());
    }
}
