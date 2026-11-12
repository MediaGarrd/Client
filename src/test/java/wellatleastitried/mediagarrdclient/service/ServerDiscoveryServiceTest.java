package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.service.ServerDiscoveryService.HostInput;

@Timeout(10)
class ServerDiscoveryServiceTest {

    private static final String HEALTH = "{\"healthy\":true,\"running\":false,\"backupRetentionCount\":4,\"backupInterval\":\"PT6H\"}";

    private final Configuration configuration = new Configuration();
    private final ClientStateService state = mock(ClientStateService.class);
    private final ServerDiscoveryService discovery = new ServerDiscoveryService(configuration, state, new ObjectMapper());
    private HttpServer mediaGarrd;
    private HttpServer otherApp;

    @BeforeEach
    void startServers() throws IOException {
        mediaGarrd = serve(HEALTH);
        otherApp = serve("<html>ok</html>");
    }

    @AfterEach
    void stopServers() {
        mediaGarrd.stop(0);
        otherApp.stop(0);
        discovery.shutdown();
    }

    @Test
    void resolvesTheGivenPort() {
        assertEquals(Optional.of(url(mediaGarrd)), discovery.resolveServerByIp("127.0.0.1", port(mediaGarrd)));
    }

    @Test
    void readsThePortFromTheAddress() {
        assertEquals(Optional.of(url(mediaGarrd)), discovery.resolveServerByIp("127.0.0.1:" + port(mediaGarrd), null));
    }

    @Test
    void scansEveryPortWhenTheGivenPortIsNotAMediaGarrdServer() {
        long start = System.nanoTime();

        Optional<String> found = discovery.resolveServerByIp("127.0.0.1", port(otherApp));

        assertTrue(found.isPresent(), "no MediaGarrd server found by the port scan");
        assertNotEquals(url(otherApp), found.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 15, "full port scan took too long");
    }

    @Test
    void preferredServerIsResolvedFromConfiguration() {
        configuration.setPreferredServerIp("localhost:" + port(mediaGarrd));

        assertEquals(Optional.of("http://localhost:" + port(mediaGarrd)), discovery.resolvePreferredServer());
    }

    @Test
    void connectsThePreferredServerOnlyWhileNoneIsSelected() {
        configuration.setPreferredServerIp("127.0.0.1:" + port(mediaGarrd));

        discovery.connectPreferredServer();
        when(state.getActiveServer()).thenReturn("http://other:38471");
        discovery.connectPreferredServer();

        verify(state, times(1)).setActiveServerIfBlank(url(mediaGarrd));
    }

    @Test
    void retriesQuicklyWhileNoServerIsSelected() throws NoSuchMethodException {
        Scheduled schedule = ServerDiscoveryService.class.getMethod("connectPreferredServer").getAnnotation(Scheduled.class);

        assertTrue(schedule.fixedDelay() >= 1_000 && schedule.fixedDelay() <= 5_000,
            "connectPreferredServer must use a numeric fixedDelay of 1-5 s, found " + schedule.fixedDelay() + " ms (fixedDelayString='" + schedule.fixedDelayString() + "')");
        assertTrue(schedule.initialDelay() >= 0 && schedule.initialDelay() <= 5_000,
            "connectPreferredServer must use a numeric initialDelay of at most 5 s, found " + schedule.initialDelay() + " ms (initialDelayString='" + schedule.initialDelayString() + "')");
    }

    @Test
    void keepsRetryingUntilThePreferredServerComesUp() throws IOException {
        int freePort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            freePort = probe.getLocalPort();
        }
        configuration.setPreferredServerIp("127.0.0.1:" + freePort);

        discovery.connectPreferredServer();
        discovery.connectPreferredServer();
        verify(state, never()).setActiveServerIfBlank(anyString());

        HttpServer late = serve(HEALTH, freePort);
        try {
            discovery.connectPreferredServer();
            verify(state, times(1)).setActiveServerIfBlank("http://127.0.0.1:" + freePort);
        } finally {
            late.stop(0);
        }
    }

    @Test
    void abandonsAHangingResolveAtTheDeadlineAndNeverOverlapsAttempts() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Duration deadline = Duration.ofMillis(300);
        ServerDiscoveryService hanging = new ServerDiscoveryService(configuration, state, new ObjectMapper(), pool, deadline) {
            @Override
            public Optional<String> resolvePreferredServer() {
                attempts.incrementAndGet();
                long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (release.getCount() > 0 && System.nanoTime() < end) {
                    try {
                        release.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                    }
                }
                return Optional.empty();
            }
        };
        try {
            long start = System.nanoTime();
            hanging.connectPreferredServer();
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsed < deadline.toMillis() + 1_500, "tick blocked for " + elapsed + " ms");

            hanging.connectPreferredServer();
            hanging.connectPreferredServer();
            assertEquals(1, attempts.get());

            release.countDown();
            long waitUntil = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (attempts.get() < 2 && System.nanoTime() < waitUntil) {
                hanging.connectPreferredServer();
                Thread.sleep(20);
            }
            assertEquals(2, attempts.get());
        } finally {
            release.countDown();
            hanging.shutdown();
            pool.shutdownNow();
        }
    }

    @Test
    void anAttemptCancelledBeforeItStartsDoesNotBlockLaterAttempts() {
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        ExecutorService deferred = new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                queued.add(command);
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
        ServerDiscoveryService service = new ServerDiscoveryService(configuration, state, new ObjectMapper(), deferred, Duration.ofMillis(100));

        service.connectPreferredServer();
        assertEquals(1, queued.size());
        service.connectPreferredServer();

        assertEquals(2, queued.size(), "the guard stayed set after the attempt was cancelled before starting");
        queued.forEach(Runnable::run);
        verify(state, never()).setActiveServerIfBlank(anyString());
    }

    @Test
    void doesNotProbeOnceAServerIsSelected() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        mediaGarrd.stop(0);
        mediaGarrd = serve(HEALTH, 0, requests);
        configuration.setPreferredServerIp("127.0.0.1:" + port(mediaGarrd));
        when(state.getActiveServer()).thenReturn("http://other:38471");

        discovery.connectPreferredServer();
        discovery.connectPreferredServer();

        assertEquals(0, requests.get());
        verify(state, never()).setActiveServerIfBlank(anyString());
    }

    @Test
    void givesUpQuicklyOnAnUnreachableHost() {
        long start = System.nanoTime();

        assertEquals(Optional.empty(), discovery.resolveServerByIp("192.0.2.1", 8123));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 3, "unreachable host was port scanned");
    }

    @Test
    void ignoresUnresolvableHosts() {
        assertEquals(Optional.empty(), discovery.resolveServerByIp("no-such-host.invalid", null));
        assertEquals(Optional.empty(), discovery.resolveServerByIp("  ", null));
    }

    @Test
    void rejectsHealthResponsesThatAreNotFromAMediaGarrdServer() throws IOException {
        HttpServer lookalike = serve("{\"status\":\"UP\"}");
        try {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            assertFalse(discovery.isMediaGarrdServer(new ServerDiscoveryService.Candidate("127.0.0.1", loopback, port(lookalike))));
            assertFalse(discovery.isMediaGarrdServer(new ServerDiscoveryService.Candidate("127.0.0.1", loopback, port(otherApp))));
            assertTrue(discovery.isMediaGarrdServer(new ServerDiscoveryService.Candidate("127.0.0.1", loopback, port(mediaGarrd))));
        } finally {
            lookalike.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"24, 254, 192.168.1.1, 192.168.1.254", "16, 254, 192.168.1.1, 192.168.1.254", "30, 2, 192.168.1.77, 192.168.1.78", "32, 2, 192.168.1.77, 192.168.1.78"})
    void listsTheHostsOfTheLocalSubnet(int prefix, int count, String first, String last) throws IOException {
        List<InetAddress> hosts = ServerDiscoveryService.subnetHosts((Inet4Address) InetAddress.getByName("192.168.1.77"), prefix);

        assertEquals(count, hosts.size());
        assertEquals(first, hosts.getFirst().getHostAddress());
        assertEquals(last, hosts.getLast().getHostAddress());
    }

    @ParameterizedTest
    @CsvSource(value = {"192.168.1.5, 192.168.1.5, null", "192.168.1.5:9000, 192.168.1.5, 9000", "http://server:38471/, server, 38471", "[::1]:8080, ::1, 8080"}, nullValues = "null")
    void parsesHostsWithOptionalPorts(String input, String host, Integer port) {
        assertEquals(new HostInput(host, port), ServerDiscoveryService.parseHost(input));
    }

    @Test
    void rejectsInputWithoutAHost() {
        assertNull(ServerDiscoveryService.parseHost("http://"));
    }

    private static HttpServer serve(String body) throws IOException {
        return serve(body, 0);
    }

    private static HttpServer serve(String body, int port) throws IOException {
        return serve(body, port, new AtomicInteger());
    }

    private static HttpServer serve(String body, int port, AtomicInteger requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static int port(HttpServer server) {
        return server.getAddress().getPort();
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + port(server);
    }
}
