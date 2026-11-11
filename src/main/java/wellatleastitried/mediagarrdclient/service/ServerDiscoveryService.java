package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.api.Endpoints;

@Service
public class ServerDiscoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerDiscoveryService.class);

    private static final List<Integer> COMMON_PORTS = List.of(38471, 8080, 80);
    private static final List<String> VIRTUAL_INTERFACE_PREFIXES = List.of("docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "cni", "flannel", "cali", "tun", "tap", "wg", "zt", "tailscale");
    private static final int MIN_SCAN_PREFIX_LENGTH = 24;
    private static final int FIRST_UNPRIVILEGED_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int MAX_CONCURRENT_PROBES = 256;
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SCAN_DEADLINE = Duration.ofSeconds(20);

    enum Probe { OPEN, REFUSED, UNREACHABLE }

    record Candidate(String host, InetAddress address, int port) {
        String url() {
            return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }
    }

    record HostInput(String host, Integer port) {
    }

    private record ProbeResult(Probe probe, String server) {
    }

    private record QuickResult(Optional<String> server, boolean hostAlive) {
    }

    private final Configuration configuration;
    private final ClientStateService stateService;
    private final ObjectMapper mapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT.multipliedBy(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore probeSlots = new Semaphore(MAX_CONCURRENT_PROBES);

    public ServerDiscoveryService(Configuration configuration, ClientStateService stateService, ObjectMapper mapper) {
        this.configuration = configuration;
        this.stateService = stateService;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 30_000)
    public void connectPreferredServer() {
        String active = stateService.getActiveServer();
        if (active == null || active.isBlank()) {
            resolvePreferredServer().ifPresent(stateService::setActiveServer);
        }
    }

    public List<String> discover() {
        long start = System.nanoTime();
        Set<String> discovered = new LinkedHashSet<>();
        resolvePreferredServer().ifPresent(discovered::add);

        List<InetAddress> hosts = localSubnetHosts();
        List<Integer> ports = quickPorts(null);
        List<Candidate> candidates = hosts.stream()
            .flatMap(host -> ports.stream().map(port -> new Candidate(host.getHostAddress(), host, port)))
            .toList();
        LOGGER.info("Scanning {} host(s) on ports {} ({} candidates)", hosts.size(), ports, candidates.size());
        discovered.addAll(findServers(candidates, deadline()));

        LOGGER.info("Network discovery found {} server(s) in {} ms: {}", discovered.size(), elapsedMillis(start), discovered);
        return List.copyOf(discovered);
    }

    public Optional<String> resolvePreferredServer() {
        String preferred = configuration.getPreferredServerIp();
        if (preferred == null || preferred.isBlank()) {
            return Optional.empty();
        }
        HostInput input = parseHost(preferred);
        InetAddress address = input == null ? null : resolve(input.host());
        return address == null ? Optional.empty() : quickResolve(input.host(), address, input.port(), deadline()).server();
    }

    public Optional<String> resolveServerByIp(String input, Integer port) {
        HostInput parsed = parseHost(input);
        InetAddress address = parsed == null ? null : resolve(parsed.host());
        if (address == null) {
            LOGGER.warn("Cannot resolve a server from '{}'", input);
            return Optional.empty();
        }
        Integer preferredPort = port != null ? port : parsed.port();
        long deadline = deadline();
        QuickResult quick = quickResolve(parsed.host(), address, preferredPort, deadline);
        if (quick.server().isPresent() || !quick.hostAlive()) {
            return quick.server();
        }

        LOGGER.info("No MediaGarrd server on the common ports of {}, scanning ports {}-{}", parsed.host(), FIRST_UNPRIVILEGED_PORT, MAX_PORT);
        long start = System.nanoTime();
        Set<Integer> probed = Set.copyOf(quickPorts(preferredPort));
        List<Candidate> candidates = IntStream.rangeClosed(FIRST_UNPRIVILEGED_PORT, MAX_PORT)
            .filter(candidatePort -> !probed.contains(candidatePort))
            .mapToObj(candidatePort -> new Candidate(parsed.host(), address, candidatePort))
            .toList();
        Optional<String> found = findServers(candidates, deadline).stream().findFirst();
        LOGGER.info("Port scan of {} finished in {} ms: {}", parsed.host(), elapsedMillis(start), found.orElse("no server found"));
        return found;
    }

    private QuickResult quickResolve(String host, InetAddress address, Integer preferredPort, long deadline) {
        List<CompletableFuture<ProbeResult>> probes = quickPorts(preferredPort).stream()
            .map(port -> probeAsync(new Candidate(host, address, port), deadline))
            .toList();
        List<ProbeResult> results = probes.stream().map(CompletableFuture::join).toList();
        Optional<String> server = results.stream().map(ProbeResult::server).filter(Objects::nonNull).findFirst();
        boolean alive = results.stream().anyMatch(result -> result.probe() != Probe.UNREACHABLE);
        server.ifPresentOrElse(
            url -> LOGGER.info("Resolved MediaGarrd server at {}", url),
            () -> LOGGER.debug("No MediaGarrd server on the common ports of {} (host {})", host, alive ? "reachable" : "unreachable"));
        return new QuickResult(server, alive);
    }

    private List<String> findServers(List<Candidate> candidates, long deadline) {
        List<CompletableFuture<ProbeResult>> probes = candidates.stream().map(candidate -> probeAsync(candidate, deadline)).toList();
        return probes.stream()
            .map(CompletableFuture::join)
            .map(ProbeResult::server)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private CompletableFuture<ProbeResult> probeAsync(Candidate candidate, long deadline) {
        return CompletableFuture.supplyAsync(() -> {
            Probe probe = connect(candidate, deadline);
            return new ProbeResult(probe, probe == Probe.OPEN && isMediaGarrdServer(candidate) ? candidate.url() : null);
        }, executor);
    }

    private Probe connect(Candidate candidate, long deadline) {
        try {
            probeSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Probe.UNREACHABLE;
        }
        try (Socket socket = new Socket()) {
            if (System.nanoTime() > deadline) {
                return Probe.UNREACHABLE;
            }
            socket.connect(new InetSocketAddress(candidate.address(), candidate.port()), (int) CONNECT_TIMEOUT.toMillis());
            return Probe.OPEN;
        } catch (ConnectException e) {
            return e.getMessage() != null && e.getMessage().contains("refused") ? Probe.REFUSED : Probe.UNREACHABLE;
        } catch (IOException | IllegalArgumentException e) {
            return Probe.UNREACHABLE;
        } finally {
            probeSlots.release();
        }
    }

    boolean isMediaGarrdServer(Candidate candidate) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(candidate.url() + Endpoints.SERVER_HEALTH.getPath()))
            .timeout(HEALTH_TIMEOUT)
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode health = mapper.readTree(response.body());
            return health.path("healthy").isBoolean() && health.has("backupInterval");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.debug("{} is not a MediaGarrd server: {}", candidate.url(), e.getMessage());
            return false;
        }
    }

    private List<Integer> quickPorts(Integer preferredPort) {
        return Stream.concat(Stream.of(preferredPort, configuration.getServerPort()), COMMON_PORTS.stream())
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private List<InetAddress> localSubnetHosts() {
        Set<InetAddress> hosts = new LinkedHashSet<>();
        try {
            for (NetworkInterface networkInterface : NetworkInterface.networkInterfaces().toList()) {
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual() || isVirtualBridge(networkInterface)) {
                    continue;
                }
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address ipv4) {
                        hosts.addAll(subnetHosts(ipv4, address.getNetworkPrefixLength()));
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.warn("Could not list network interfaces: {}", e.getMessage());
        }
        return List.copyOf(hosts);
    }

    static List<InetAddress> subnetHosts(Inet4Address address, int prefixLength) {
        int prefix = Math.min(Math.max(prefixLength, MIN_SCAN_PREFIX_LENGTH), 30);
        int ip = toInt(address.getAddress());
        int mask = -1 << (32 - prefix);
        int network = ip & mask;
        int broadcast = network | ~mask;
        List<InetAddress> hosts = new ArrayList<>(broadcast - network - 1);
        for (int host = network + 1; host < broadcast; host++) {
            hosts.add(toAddress(host));
        }
        return hosts;
    }

    private static boolean isVirtualBridge(NetworkInterface networkInterface) {
        String name = networkInterface.getName();
        return VIRTUAL_INTERFACE_PREFIXES.stream().anyMatch(name::startsWith);
    }

    static HostInput parseHost(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        try {
            URI uri = URI.create(trimmed.contains("://") ? trimmed : "http://" + trimmed);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            String host = uri.getHost().startsWith("[") ? uri.getHost().substring(1, uri.getHost().length() - 1) : uri.getHost();
            return new HostInput(host, uri.getPort() > 0 ? uri.getPort() : null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int toInt(byte[] bytes) {
        return (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    private static InetAddress toAddress(int ip) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            LOGGER.debug("Cannot resolve host {}: {}", host, e.getMessage());
            return null;
        }
    }

    private static long deadline() {
        return System.nanoTime() + SCAN_DEADLINE.toNanos();
    }

    private static long elapsedMillis(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
