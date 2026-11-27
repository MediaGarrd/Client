package wellatleastitried.mediagarrdclient.api;

import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import jakarta.servlet.ServletException;

import wellatleastitried.mediagarrdclient.service.BackupRunFailedException;
import wellatleastitried.mediagarrdclient.service.BackupRunTimeoutException;
import wellatleastitried.mediagarrdclient.service.ConflictException;
import wellatleastitried.mediagarrdclient.service.InvalidRequestException;
import wellatleastitried.mediagarrdclient.service.NotFoundException;
import wellatleastitried.mediagarrdclient.service.PairingRequiredException;
import wellatleastitried.mediagarrdclient.service.RequestTimeouts;
import wellatleastitried.mediagarrdclient.service.ServerRedirectException;
import wellatleastitried.mediagarrdclient.service.UpstreamResponseException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record ServerError(String error) {
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> serverRejected(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = null;
        if (!e.getStatusCode().is5xxServerError()) {
            try {
                ServerError body = e.getResponseBodyAs(ServerError.class);
                message = body == null ? null : body.error();
            } catch (RuntimeException ignored) {
            }
        }
        String reason = message == null ? "MediaGarrd server responded with " + status : message;
        LOGGER.warn("MediaGarrd server rejected request: {}", reason);
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null && retryAfter.matches("\\d{1,6}")) {
                return ResponseEntity.status(e.getStatusCode()).header(HttpHeaders.RETRY_AFTER, retryAfter).body(Map.of("error", reason));
            }
        }
        return error(e.getStatusCode(), reason);
    }

    @ExceptionHandler(PairingRequiredException.class)
    public ResponseEntity<Map<String, String>> pairingRequired(PairingRequiredException e) {
        LOGGER.warn("Pairing required: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage(), "pairing", "required"));
    }

    @ExceptionHandler(ServerRedirectException.class)
    public ResponseEntity<Map<String, String>> serverRedirected(ServerRedirectException e) {
        LOGGER.warn("MediaGarrd server redirected the request: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(UpstreamResponseException.class)
    public ResponseEntity<Map<String, String>> upstreamInvalid(UpstreamResponseException e) {
        LOGGER.warn("MediaGarrd server sent an unusable response: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(BackupRunFailedException.class)
    public ResponseEntity<Map<String, String>> backupRunFailed(BackupRunFailedException e) {
        LOGGER.warn("Backup run failed on the server: {}", e.getMessage());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(BackupRunTimeoutException.class)
    public ResponseEntity<Map<String, String>> backupRunTimedOut(BackupRunTimeoutException e) {
        LOGGER.warn("Backup run timed out: {}", e.getMessage());
        return error(HttpStatus.GATEWAY_TIMEOUT, e.getMessage());
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> serverUnreachable(ResourceAccessException e) {
        if (RequestTimeouts.isTimeout(e)) {
            LOGGER.warn("MediaGarrd server did not respond in time: {}", e.getMessage());
            return error(HttpStatus.GATEWAY_TIMEOUT, "MediaGarrd server did not respond in time");
        }
        LOGGER.warn("MediaGarrd server unreachable: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "MediaGarrd server is unreachable");
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidRequestException e) {
        LOGGER.warn("Rejected request: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        LOGGER.warn("Rejected request: {}", e.getMessage());
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        LOGGER.warn("Rejected request: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        if (tooLarge(e)) {
            LOGGER.warn("Request body too large");
            return error(HttpStatus.CONTENT_TOO_LARGE, "Request body is too large");
        }
        LOGGER.warn("Unreadable request body: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or malformed");
    }

    @ExceptionHandler({RuntimeException.class, ServletException.class})
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            LOGGER.warn("Rejected request: {}", e.getMessage());
            return error(response.getStatusCode(), response.getBody().getDetail());
        }
        LOGGER.error("Request failed", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }

    private static boolean tooLarge(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message) {
        String fallback = status instanceof HttpStatus known ? known.getReasonPhrase() : "Request failed";
        return ResponseEntity.status(status).body(Map.of("error", Objects.requireNonNullElse(message, fallback)));
    }
}
