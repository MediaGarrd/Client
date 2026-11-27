package wellatleastitried.mediagarrdclient.api;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import wellatleastitried.mediagarrdclient.service.DashboardService;
import wellatleastitried.mediagarrdclient.service.SessionService;

@RestController
@RequestMapping("/api/client")
public class ClientEventsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEventsController.class);

    private final DashboardService dashboard;
    private final ClientEventBroadcaster broadcaster;

    public ClientEventsController(DashboardService dashboard, ClientEventBroadcaster broadcaster) {
        this.dashboard = dashboard;
        this.broadcaster = broadcaster;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(HttpServletRequest request) {
        String sessionKey = SessionService.keyOf(SessionGate.sessionToken(request));
        return dashboard.subscribe(initial -> broadcaster.subscribe(sessionKey, initial))
            .map(emitter -> ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(emitter))
            .orElseGet(() -> ResponseEntity.status(503).build());
    }

    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    public void streamEnded(Exception e) {
        LOGGER.debug("Browser event stream ended: {}", e.getMessage());
    }
}
