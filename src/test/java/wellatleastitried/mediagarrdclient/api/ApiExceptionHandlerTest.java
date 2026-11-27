package wellatleastitried.mediagarrdclient.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import wellatleastitried.mediagarrdclient.service.ConflictException;
import wellatleastitried.mediagarrdclient.service.InvalidRequestException;
import wellatleastitried.mediagarrdclient.service.NotFoundException;
import wellatleastitried.mediagarrdclient.service.UpstreamResponseException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anUnexpectedFailureAnswersWithAGenericMessageInsteadOfItsDetails() {
        var response = handler.failed(new IllegalStateException("Failed to save client state to /var/lib/mediagarrd/client/state.json"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(Map.of("error", "Internal error"), response.getBody());
    }

    @Test
    void aRejectedRequestKeepsItsMessage() {
        var response = handler.invalid(new InvalidRequestException("PIN must be 6 digits"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "PIN must be 6 digits"), response.getBody());
    }

    @Test
    void anIllegalArgumentIsNotMistakenForARejectedRequest() {
        var response = handler.failed(new IllegalArgumentException("/var/lib/mediagarrd is not a directory"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(Map.of("error", "Internal error"), response.getBody());
    }

    @Test
    void notFoundAndConflictKeepTheirMessagesAndStatuses() {
        var missing = handler.notFound(new NotFoundException("Backup not found: b9"));
        var conflict = handler.conflict(new ConflictException("A password is already set"));

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(Map.of("error", "Backup not found: b9"), missing.getBody());
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(Map.of("error", "A password is already set"), conflict.getBody());
    }

    @Test
    void aSpringErrorResponseKeepsItsClientStatusAndDetail() {
        var response = handler.failed(new ResponseStatusException(HttpStatus.NOT_FOUND, "Pickup task not found: gone"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(Map.of("error", "Pickup task not found: gone"), response.getBody());
    }

    @Test
    void aSpringServerErrorResponseBecomesTheGenericBody() {
        var response = handler.failed(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "/etc/secret is missing"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(Map.of("error", "Internal error"), response.getBody());
    }

    @Test
    void aServerFailureIsNotReflectedBack() {
        var response = handler.serverRejected(new org.springframework.web.client.HttpServerErrorException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "{\"error\":\"Disk /srv/backups is full\"}".getBytes(), java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(Map.of("error", "MediaGarrd server responded with 500"), response.getBody());
    }

    @Test
    void anUnusableServerReplyIsABadGateway() {
        var response = handler.upstreamInvalid(new UpstreamResponseException("The server did not return a token"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(Map.of("error", "The server did not return a token"), response.getBody());
    }

    private static org.springframework.web.client.HttpClientErrorException tooManyRequests(String retryAfter, String body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (retryAfter != null) {
            headers.add("Retry-After", retryAfter);
        }
        var e = org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, new byte[0], java.nio.charset.StandardCharsets.UTF_8);
        e.setBodyConvertFunction(type -> new ApiExceptionHandler.ServerError(body));
        return e;
    }

    @Test
    void aProxiedTooManyRequestsKeepsItsRetryAfterAndMessage() {
        var response = handler.serverRejected(tooManyRequests("7", "Wait 7 seconds before sending another test"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("7", response.getHeaders().getFirst("Retry-After"));
        assertEquals(Map.of("error", "Wait 7 seconds before sending another test"), response.getBody());
    }

    @Test
    void aProxiedTooManyRequestsWithoutAUsableRetryAfterHasNone() {
        var missing = handler.serverRejected(tooManyRequests(null, "Slow down"));
        var odd = handler.serverRejected(tooManyRequests("soon\r\nX-Evil: 1", "Slow down"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, missing.getStatusCode());
        assertEquals(null, missing.getHeaders().getFirst("Retry-After"));
        assertEquals(null, odd.getHeaders().getFirst("Retry-After"));
        assertEquals(Map.of("error", "Slow down"), odd.getBody());
    }

    @Test
    void otherProxiedRejectionsGetNoRetryAfter() {
        var rejected = org.springframework.web.client.HttpClientErrorException.create(
            HttpStatus.BAD_REQUEST, "Bad Request", new org.springframework.http.HttpHeaders(), new byte[0], java.nio.charset.StandardCharsets.UTF_8);
        rejected.setBodyConvertFunction(type -> new ApiExceptionHandler.ServerError("Webhook URL must use http or https"));
        var response = handler.serverRejected(rejected);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(null, response.getHeaders().getFirst("Retry-After"));
        assertEquals(Map.of("error", "Webhook URL must use http or https"), response.getBody());
    }
}
