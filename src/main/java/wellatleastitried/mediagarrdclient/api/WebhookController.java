package wellatleastitried.mediagarrdclient.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import wellatleastitried.mediagarrdclient.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdclient.dto.WebhookTestDto;
import wellatleastitried.mediagarrdclient.dto.WebhookUpdateRequest;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;

@RestController
@RequestMapping("/api/client/notifications/webhook")
public class WebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    private final ClientStateService stateService;
    private final ServerApiService serverApiService;

    public WebhookController(ClientStateService stateService, ServerApiService serverApiService) {
        this.stateService = stateService;
        this.serverApiService = serverApiService;
    }

    @GetMapping
    public WebhookStatusDto webhook() {
        String activeServer = stateService.requireActiveServer();
        LOGGER.debug("GET /api/client/notifications/webhook, forwarding to {}", activeServer);
        return serverApiService.webhook(activeServer);
    }

    @PutMapping
    public WebhookStatusDto updateWebhook(@RequestBody WebhookUpdateRequest request) {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("PUT /api/client/notifications/webhook, target={}", activeServer);
        return serverApiService.updateWebhook(activeServer, request.url());
    }

    @PostMapping("/test")
    public WebhookTestDto testWebhook() {
        String activeServer = stateService.requireActiveServer();
        LOGGER.info("POST /api/client/notifications/webhook/test, target={}", activeServer);
        return serverApiService.testWebhook(activeServer);
    }
}
