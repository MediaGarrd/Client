package wellatleastitried.mediagarrdclient.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;

@RestController
@RequestMapping("/api/client")
public class ServerConfigController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfigController.class);

    private final ClientStateService stateService;
    private final ServerApiService serverApiService;

    public ServerConfigController(ClientStateService stateService, ServerApiService serverApiService) {
        this.stateService = stateService;
        this.serverApiService = serverApiService;
    }

    @GetMapping("/server-config")
    public ServerConfigDto serverConfig() {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("GET /api/client/server-config, forwarding to {}", activeServer);
        return serverApiService.serverConfig(activeServer);
    }

    @PutMapping("/server-config")
    public ServerConfigDto updateServerConfig(@RequestBody ServerConfigDto request) {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("PUT /api/client/server-config, request={}, target={}", request, activeServer);
        ServerConfigDto config = serverApiService.updateServerConfig(activeServer, request.backupLocation(), request.retentionCount());
        if (request.backupInterval() == null) {
            return config;
        }
        ServerStatusDto status = serverApiService.updateServerSchedule(activeServer, request.backupInterval());
        return new ServerConfigDto(config.backupLocation(), config.retentionCount(), status.backupInterval());
    }

    @GetMapping("/services")
    public List<ServiceDto> services() {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("GET /api/client/services, forwarding to {}", activeServer);
        return serverApiService.listServices(activeServer);
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceDto addService(@RequestBody ServiceDto service) {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("POST /api/client/services, name={}, target={}", service.name(), activeServer);
        return serverApiService.addService(activeServer, service);
    }

    @PutMapping("/services/{name}")
    public ServiceDto updateService(@PathVariable String name, @RequestBody ServiceDto service) {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("PUT /api/client/services/{}, name={}, target={}", name, service.name(), activeServer);
        return serverApiService.updateService(activeServer, name, service);
    }

    @DeleteMapping("/services/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeService(@PathVariable String name) {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("DELETE /api/client/services/{}, target={}", name, activeServer);
        serverApiService.removeService(activeServer, name);
    }
}
