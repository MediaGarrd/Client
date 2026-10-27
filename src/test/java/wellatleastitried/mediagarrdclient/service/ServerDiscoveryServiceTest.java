package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.service.ServerDiscoveryService.HostInput;

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

        verify(state, times(1)).setActiveServer(url(mediaGarrd));
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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
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
