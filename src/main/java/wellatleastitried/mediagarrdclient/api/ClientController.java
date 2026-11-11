package wellatleastitried.mediagarrdclient.api;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.ClientStatusDto;
import wellatleastitried.mediagarrdclient.dto.ConnectionStatusDto;
import wellatleastitried.mediagarrdclient.dto.IntervalUpdateRequest;
import wellatleastitried.mediagarrdclient.dto.PickupProgressDto;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;
import wellatleastitried.mediagarrdclient.dto.ServerSelectionRequest;
import wellatleastitried.mediagarrdclient.service.BackupPickupService;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;
import wellatleastitried.mediagarrdclient.service.ServerDiscoveryService;

@RestController
@RequestMapping("/api/client")
public class ClientController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientController.class);

    private final ClientStateService stateService;
    private final ServerDiscoveryService discoveryService;
    private final ServerApiService serverApiService;
    private final BackupPickupService pickupService;

    public ClientController(
        ClientStateService stateService,
        ServerDiscoveryService discoveryService,
        ServerApiService serverApiService,
        BackupPickupService pickupService
    ) {
        this.stateService = stateService;
        this.discoveryService = discoveryService;
        this.serverApiService = serverApiService;
        this.pickupService = pickupService;
    }

    @GetMapping("/status")
    public ClientStatusDto status() {
        discoveryService.connectPreferredServer();
        return currentStatus();
    }

    @PostMapping("/discover")
    public List<String> discover() {
        List<String> discovered = discoveryService.discover();
        stateService.updateKnownServers(discovered);
        return discovered;
    }

    @GetMapping("/server-health")
    public ConnectionStatusDto serverHealth() {
        String activeServer = stateService.getActiveServer();
        if (isBlank(activeServer)) {
            return new ConnectionStatusDto("", false);
        }
        try {
            return new ConnectionStatusDto(activeServer, true, serverApiService.health(activeServer));
        } catch (RuntimeException e) {
            LOGGER.debug("{} is unreachable: {}", activeServer, e.getMessage());
            return new ConnectionStatusDto(activeServer, false);
        }
    }

    @PutMapping("/server")
    public ClientStatusDto setServer(@RequestBody ServerSelectionRequest request) {
        if (!isBlank(request.serverUrl())) {
            stateService.setActiveServer(normalizeServerUrl(request.serverUrl()));
            return currentStatus();
        }
        if (isBlank(request.serverIp())) {
            throw new IllegalArgumentException("Either serverUrl or serverIp must be provided");
        }
        Integer port = request.serverPort();
        if (port != null && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("serverPort must be between 1 and 65535");
        }
        String resolved = discoveryService.resolveServerByIp(request.serverIp(), port)
            .orElseThrow(() -> new IllegalArgumentException("No MediaGarrd server found at " + request.serverIp().trim()));
        stateService.setActiveServer(resolved);
        return currentStatus();
    }

    @GetMapping("/backups")
    public List<BackupDto> listBackups() {
        return serverApiService.listBackups(stateService.requireActiveServer());
    }

    @PostMapping("/backups/run")
    public void runBackup() {
        serverApiService.triggerBackup(stateService.requireActiveServer());
    }

    @PostMapping("/pickup")
    public PickupStartDto pickupLatest() {
        return pickupService.startPickupLatestTask();
    }

    @PostMapping("/pickup/{id}")
    public PickupStartDto pickupById(@PathVariable String id) {
        return pickupService.startPickupByIdTask(id);
    }

    @GetMapping("/pickup/progress/{taskId}")
    public PickupProgressDto pickupProgress(@PathVariable String taskId) {
        return pickupService.pickupProgress(taskId);
    }

    @PutMapping("/pickup-interval")
    public ClientStatusDto updatePickupInterval(@RequestBody IntervalUpdateRequest request) {
        pickupService.updatePickupInterval(request.interval());
        return currentStatus();
    }

    private ClientStatusDto currentStatus() {
        return new ClientStatusDto(stateService.getActiveServer(), stateService.getPickupInterval(), stateService.getKnownServers());
    }

    static String normalizeServerUrl(String serverUrl) {
        String trimmed = serverUrl.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(trimmed);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && !isBlank(uri.getHost())) {
                return trimmed;
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalArgumentException("serverUrl must be an http:// or https:// URL: " + serverUrl);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
