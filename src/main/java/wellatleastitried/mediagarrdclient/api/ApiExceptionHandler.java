package wellatleastitried.mediagarrdclient.api;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record ServerError(String error) {
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> serverRejected(RestClientResponseException e) {
        String message = null;
        try {
            ServerError body = e.getResponseBodyAs(ServerError.class);
            message = body == null ? null : body.error();
        } catch (RuntimeException ignored) {
        }
        String reason = message == null ? "MediaGarrd server responded with " + e.getStatusCode().value() : message;
        LOGGER.warn("MediaGarrd server rejected request: {}", reason);
        return error(e.getStatusCode(), reason);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> serverUnreachable(ResourceAccessException e) {
        LOGGER.warn("MediaGarrd server unreachable: {}", e.getMessage());
        return e.getCause() instanceof HttpTimeoutException || e.getCause() instanceof SocketTimeoutException
            ? error(HttpStatus.GATEWAY_TIMEOUT, "MediaGarrd server did not respond in time")
            : error(HttpStatus.BAD_GATEWAY, "MediaGarrd server is unreachable");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejected(ResponseStatusException e) {
        LOGGER.warn("Rejected request: {}", e.getReason());
        return error(e.getStatusCode(), e.getReason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        LOGGER.warn("Unreadable request body: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or malformed");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> failed(RuntimeException e) {
        HttpStatus status = e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            LOGGER.error("Request failed: {}", e.getMessage());
        } else {
            LOGGER.warn("Rejected request: {}", e.getMessage());
        }
        return error(status, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message) {
        String fallback = status instanceof HttpStatus known ? known.getReasonPhrase() : "Request failed";
        return ResponseEntity.status(status).body(Map.of("error", Objects.requireNonNullElse(message, fallback)));
    }
}
