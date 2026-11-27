package wellatleastitried.mediagarrdclient.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.ClientStatusDto;
import wellatleastitried.mediagarrdclient.dto.DashboardState;
import wellatleastitried.mediagarrdclient.dto.IntervalUpdateRequest;
import wellatleastitried.mediagarrdclient.dto.PickupProgressDto;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;
import wellatleastitried.mediagarrdclient.dto.ServerSelectionRequest;
import wellatleastitried.mediagarrdclient.service.BackupPickupService;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.DashboardService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;
import wellatleastitried.mediagarrdclient.service.ServerDiscoveryService;
import wellatleastitried.mediagarrdclient.service.ServerSelectionService;

@RestController
@RequestMapping("/api/client")
public class ClientController {

    private final ClientStateService stateService;
    private final ServerDiscoveryService discoveryService;
    private final ServerApiService serverApiService;
    private final BackupPickupService pickupService;
    private final DashboardService dashboardService;
    private final ServerSelectionService selectionService;

    public ClientController(
        ClientStateService stateService,
        ServerDiscoveryService discoveryService,
        ServerApiService serverApiService,
        BackupPickupService pickupService,
        DashboardService dashboardService,
        ServerSelectionService selectionService
    ) {
        this.stateService = stateService;
        this.discoveryService = discoveryService;
        this.serverApiService = serverApiService;
        this.pickupService = pickupService;
        this.dashboardService = dashboardService;
        this.selectionService = selectionService;
    }

    @GetMapping("/status")
    public ClientStatusDto status() {
        return currentStatus();
    }

    @GetMapping("/dashboard")
    public DashboardState dashboard() {
        return dashboardService.state();
    }

    @PostMapping("/discover")
    public List<String> discover() {
        List<String> discovered = discoveryService.discover();
        stateService.updateKnownServers(discovered);
        return discovered;
    }

    @PutMapping("/server")
    public ClientStatusDto setServer(@RequestBody ServerSelectionRequest request) {
        selectionService.select(request);
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
}
