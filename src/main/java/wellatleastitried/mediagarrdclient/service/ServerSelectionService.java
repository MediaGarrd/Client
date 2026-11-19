package wellatleastitried.mediagarrdclient.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import wellatleastitried.mediagarrdclient.dto.ServerSelectionRequest;

@Service
public class ServerSelectionService {

    private final ClientStateService stateService;
    private final ServerDiscoveryService discoveryService;

    public ServerSelectionService(ClientStateService stateService, ServerDiscoveryService discoveryService) {
        this.stateService = stateService;
        this.discoveryService = discoveryService;
    }

    public void select(ServerSelectionRequest request) {
        if (StringUtils.hasText(request.serverUrl())) {
            String serverUrl = ServerUrls.requireHttp(request.serverUrl());
            if (!stateService.isKnownServer(serverUrl) && !discoveryService.isMediaGarrdServer(serverUrl)) {
                throw new InvalidRequestException("No MediaGarrd server found at " + serverUrl);
            }
            stateService.setActiveServer(serverUrl);
            return;
        }
        if (!StringUtils.hasText(request.serverIp())) {
            throw new InvalidRequestException("Either serverUrl or serverIp must be provided");
        }
        Integer port = request.serverPort();
        if (port != null && (port < 1 || port > 65535)) {
            throw new InvalidRequestException("serverPort must be between 1 and 65535");
        }
        String resolved = discoveryService.resolveServerByIp(request.serverIp(), port)
            .orElseThrow(() -> new InvalidRequestException("No MediaGarrd server found at " + request.serverIp().trim()));
        stateService.setActiveServer(resolved);
    }
}
