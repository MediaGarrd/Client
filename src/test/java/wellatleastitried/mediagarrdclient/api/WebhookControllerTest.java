package wellatleastitried.mediagarrdclient.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import wellatleastitried.mediagarrdclient.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdclient.dto.WebhookTestDto;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.PairingRequiredException;
import wellatleastitried.mediagarrdclient.service.ServerApiService;

@WebMvcTest(controllers = WebhookController.class)
@Import(TestSession.class)
class WebhookControllerTest {

    private static final String SERVER = "http://server:8080";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ClientStateService stateService;

    @MockitoBean
    ServerApiService serverApiService;

    @BeforeEach
    void setUp() {
        when(stateService.requireActiveServer()).thenReturn(SERVER);
    }

    @Test
    void forwardsTheStatusReadToTheActiveServer() throws Exception {
        when(serverApiService.webhook(SERVER)).thenReturn(new WebhookStatusDto(true, "https://hooks.slack.com"));

        mockMvc.perform(get("/api/client/notifications/webhook"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.target").value("https://hooks.slack.com"));
    }

    @Test
    void forwardsASetAndAClear() throws Exception {
        when(serverApiService.updateWebhook(SERVER, "https://hooks.slack.com/services/T/B/X")).thenReturn(new WebhookStatusDto(true, "https://hooks.slack.com"));
        when(serverApiService.updateWebhook(SERVER, "")).thenReturn(new WebhookStatusDto(false, null));

        mockMvc.perform(put("/api/client/notifications/webhook").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://hooks.slack.com/services/T/B/X\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true));
        mockMvc.perform(put("/api/client/notifications/webhook").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void forwardsTheTestAndItsResult() throws Exception {
        when(serverApiService.testWebhook(SERVER)).thenReturn(new WebhookTestDto(true, 204, null));

        mockMvc.perform(post("/api/client/notifications/webhook/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.delivered").value(true))
            .andExpect(jsonPath("$.status").value(204));
    }

    @Test
    void passesTheServersValidationMessageThrough() throws Exception {
        when(serverApiService.updateWebhook(SERVER, "ftp://x")).thenThrow(rejected(HttpStatus.BAD_REQUEST, new HttpHeaders(), "Webhook URL must use http or https"));

        mockMvc.perform(put("/api/client/notifications/webhook").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"ftp://x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Webhook URL must use http or https"));
    }

    @Test
    void passesTheTestCooldownAndItsRetryAfterThrough() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "8");
        when(serverApiService.testWebhook(SERVER)).thenThrow(rejected(HttpStatus.TOO_MANY_REQUESTS, headers, "Wait 8 seconds before sending another test"));

        mockMvc.perform(post("/api/client/notifications/webhook/test"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "8"))
            .andExpect(jsonPath("$.error").value("Wait 8 seconds before sending another test"));
    }

    @Test
    void reportsAnUnreachableServerAndAMissingPairing() throws Exception {
        when(serverApiService.webhook(SERVER)).thenThrow(new ResourceAccessException("Connection refused"));
        when(serverApiService.testWebhook(SERVER)).thenThrow(new PairingRequiredException("Pair with the server first"));

        mockMvc.perform(get("/api/client/notifications/webhook"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("MediaGarrd server is unreachable"));
        mockMvc.perform(post("/api/client/notifications/webhook/test"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.pairing").value("required"));
    }

    @Test
    void theUpdateRequestDoesNotPrintTheUrl() {
        org.junit.jupiter.api.Assertions.assertFalse(
            new wellatleastitried.mediagarrdclient.dto.WebhookUpdateRequest("https://discord.com/api/webhooks/1/SECRETTOKEN").toString().contains("SECRETTOKEN"));
    }

    private static HttpClientErrorException rejected(HttpStatus status, HttpHeaders headers, String message) {
        HttpClientErrorException e = HttpClientErrorException.create(status, status.getReasonPhrase(), headers, new byte[0], java.nio.charset.StandardCharsets.UTF_8);
        e.setBodyConvertFunction(type -> new ApiExceptionHandler.ServerError(message));
        return e;
    }
}
