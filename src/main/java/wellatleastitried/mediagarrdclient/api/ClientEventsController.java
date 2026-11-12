package wellatleastitried.mediagarrdclient.api;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import wellatleastitried.mediagarrdclient.service.DashboardService;

@RestController
@RequestMapping("/api/client")
public class ClientEventsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEventsController.class);

    private final DashboardService dashboard;

    public ClientEventsController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events() {
        return dashboard.subscribe()
            .map(emitter -> ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter))
            .orElseGet(() -> ResponseEntity.status(503).build());
    }

    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    public void streamEnded(Exception e) {
        LOGGER.debug("Browser event stream ended: {}", e.getMessage());
    }
}
