package wellatleastitried.mediagarrdclient.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import wellatleastitried.mediagarrdclient.service.InvalidRequestException;

import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.DashboardState;
import wellatleastitried.mediagarrdclient.dto.HealthSummary;
import wellatleastitried.mediagarrdclient.dto.LinkState;
import wellatleastitried.mediagarrdclient.dto.PairingCodeDto;
import wellatleastitried.mediagarrdclient.dto.PairingResultDto;
import wellatleastitried.mediagarrdclient.dto.PairingState;
import wellatleastitried.mediagarrdclient.dto.PickupProgressDto;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;
import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.dto.UnpairResultDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.dto.VersionsDto;
import wellatleastitried.mediagarrdclient.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdclient.dto.WebhookTestDto;
import wellatleastitried.mediagarrdclient.golden.Fixtures;
import wellatleastitried.mediagarrdclient.service.BackupPickupService;
import wellatleastitried.mediagarrdclient.service.ClientPairingService;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.DashboardService;
import wellatleastitried.mediagarrdclient.service.LoginAttempts;
import wellatleastitried.mediagarrdclient.service.PasswordService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;
import wellatleastitried.mediagarrdclient.service.ServerDiscoveryService;
import wellatleastitried.mediagarrdclient.service.ServerSelectionService;

@WebMvcTest(controllers = {ClientController.class, PairingController.class, ServerConfigController.class, WebhookController.class, AuthController.class})
@Import({TestSession.class, ServerSelectionService.class})
class ClientApiGoldenTest {

    private static final String SERVER = "http://192.168.1.20:38471";
    private static final Instant INSTANT = Instant.parse("2026-11-20T10:15:30.123456Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ClientStateService stateService;

    @MockitoBean
    ServerDiscoveryService discoveryService;

    @MockitoBean
    ServerApiService serverApiService;

    @MockitoBean
    BackupPickupService backupPickupService;

    @MockitoBean
    DashboardService dashboardService;

    @MockitoBean
    ClientPairingService pairing;

    @MockitoBean
    PasswordService passwords;

    @MockitoBean
    LoginAttempts attempts;

    @Test
    void statusJson() throws Exception {
        when(stateService.getActiveServer()).thenReturn(SERVER);
        when(stateService.getPickupInterval()).thenReturn(Duration.ofMinutes(90));
        when(stateService.getKnownServers()).thenReturn(List.of(SERVER, "https://backup.example.net:38471"));

        mockMvc.perform(get("/api/client/status"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/status.json"), JsonCompareMode.STRICT));
    }

    @Test
    void dashboardJson() throws Exception {
        VersionStatusDto client = new VersionStatusDto("0.1.0", "0.1.0", "https://github.com/MediaGarrd/Client/releases/tag/v0.1.0", false, CheckState.OK, INSTANT, null);
        when(dashboardService.state()).thenReturn(new DashboardState(SERVER, List.of(SERVER), LinkState.LIVE, false,
            new HealthSummary(Duration.ofHours(6), 7), new VersionsDto(client, null), PairingState.PAIRED));

        mockMvc.perform(get("/api/client/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/dashboard.json"), JsonCompareMode.STRICT));
    }

    @Test
    void backupsJson() throws Exception {
        when(stateService.requireActiveServer()).thenReturn(SERVER);
        when(serverApiService.listBackups(SERVER)).thenReturn(List.of(new BackupDto("backup-2026-11-20", "mediagarrd-2026-11-20.zip", 1048576L, INSTANT)));

        mockMvc.perform(get("/api/client/backups"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/backups.json"), JsonCompareMode.STRICT));
    }

    @Test
    void serverConfigJson() throws Exception {
        when(stateService.requireActiveServer()).thenReturn(SERVER);
        when(serverApiService.serverConfig(SERVER)).thenReturn(new ServerConfigDto("/var/backups/mediagarrd", 7, Duration.ofHours(6)));

        mockMvc.perform(get("/api/client/server-config"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/server-config.json"), JsonCompareMode.STRICT));
    }

    @Test
    void webhookJson() throws Exception {
        when(stateService.requireActiveServer()).thenReturn(SERVER);
        when(serverApiService.webhook(SERVER)).thenReturn(new WebhookStatusDto(true, "https://discord.com"));
        when(serverApiService.updateWebhook(SERVER, "")).thenReturn(new WebhookStatusDto(false, null));
        when(serverApiService.testWebhook(SERVER)).thenReturn(new WebhookTestDto(false, 404, "Webhook answered 404"));

        mockMvc.perform(get("/api/client/notifications/webhook"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/webhook-status.json"), JsonCompareMode.STRICT));
        mockMvc.perform(put("/api/client/notifications/webhook").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/webhook-unset.json"), JsonCompareMode.STRICT));
        mockMvc.perform(post("/api/client/notifications/webhook/test"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/webhook-test.json"), JsonCompareMode.STRICT));
    }

    @Test
    void serverConfigUpdateParsesADurationString() throws Exception {
        when(stateService.requireActiveServer()).thenReturn(SERVER);
        when(serverApiService.updateServerConfig(SERVER, "/b", 3)).thenReturn(new ServerConfigDto("/b", 3, Duration.ofHours(6)));
        when(serverApiService.updateServerSchedule(SERVER, Duration.ofHours(2))).thenReturn(new ServerStatusDto(true, false, 3, Duration.ofHours(2)));

        mockMvc.perform(put("/api/client/server-config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"backupLocation\":\"/b\",\"retentionCount\":3,\"backupInterval\":\"PT2H\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"backupLocation\":\"/b\",\"retentionCount\":3,\"backupInterval\":\"PT2H\"}", JsonCompareMode.STRICT));
    }

    @Test
    void servicesJson() throws Exception {
        when(stateService.requireActiveServer()).thenReturn(SERVER);
        when(serverApiService.listServices(SERVER)).thenReturn(List.of(new ServiceDto("jellyfin", List.of("/srv/jellyfin/config"))));

        mockMvc.perform(get("/api/client/services"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/services.json"), JsonCompareMode.STRICT));
    }

    @Test
    void pickupJson() throws Exception {
        when(backupPickupService.startPickupLatestTask()).thenReturn(new PickupStartDto("task-1", "backup-2026-11-20", "mediagarrd-2026-11-20.zip", 1048576L));
        when(backupPickupService.pickupProgress("task-1")).thenReturn(new PickupProgressDto("task-1", "RUNNING", "backup-2026-11-20", "mediagarrd-2026-11-20.zip", 512L, 1048576L, null, null));

        mockMvc.perform(post("/api/client/pickup"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/pickup-start.json"), JsonCompareMode.STRICT));
        mockMvc.perform(get("/api/client/pickup/progress/task-1"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/pickup-progress.json"), JsonCompareMode.STRICT));
    }

    @Test
    void pickupIntervalRequestParsesADurationString() throws Exception {
        when(stateService.getActiveServer()).thenReturn(SERVER);
        when(stateService.getPickupInterval()).thenReturn(Duration.ofMinutes(90));
        when(stateService.getKnownServers()).thenReturn(List.of(SERVER, "https://backup.example.net:38471"));

        mockMvc.perform(put("/api/client/pickup-interval").contentType(MediaType.APPLICATION_JSON).content("{\"interval\":\"PT1H30M\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/status.json"), JsonCompareMode.STRICT));
        verify(backupPickupService).updatePickupInterval(Duration.ofMinutes(90));
    }

    @Test
    void pairingJson() throws Exception {
        when(pairing.pair(eq("123456"), any())).thenReturn(new PairingResultDto(true, "Golden Laptop", null));
        when(pairing.createCode()).thenReturn(new PairingCodeDto("482913", Instant.parse("2026-11-20T10:25:30.500Z")));
        when(pairing.unpair()).thenReturn(new UnpairResultDto(true, null));

        mockMvc.perform(post("/api/client/pairing").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\",\"name\":\"Golden Laptop\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/pairing-result.json"), JsonCompareMode.STRICT));
        mockMvc.perform(post("/api/client/pairing/code"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/pairing-code.json"), JsonCompareMode.STRICT));
        mockMvc.perform(delete("/api/client/pairing"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/unpair-result.json"), JsonCompareMode.STRICT));
    }

    @Test
    void deferredPairingJson() throws Exception {
        when(pairing.pair(eq("123456"), any())).thenReturn(new PairingResultDto(true, "Golden Laptop", "Paired. The server could not be reached to confirm it yet."));

        mockMvc.perform(post("/api/client/pairing").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/pairing-result-deferred.json"), JsonCompareMode.STRICT));
    }

    @Test
    void requestBodiesWithUnknownOrMissingFieldsAreAccepted() throws Exception {
        when(pairing.pair(eq("123456"), eq(null))).thenReturn(new PairingResultDto(true, null, null));

        mockMvc.perform(post("/api/client/pairing").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\",\"somethingNew\":1}"))
            .andExpect(status().isOk());
    }

    @Test
    void authStatusJson() throws Exception {
        when(passwords.isConfigured()).thenReturn(true);
        when(passwords.isFromEnvironment()).thenReturn(false);

        mockMvc.perform(get("/api/auth/status"))
            .andExpect(status().isOk())
            .andExpect(content().json(Fixtures.read("client/auth-status.json"), JsonCompareMode.STRICT));
    }

    @Test
    void errorJson() throws Exception {
        when(stateService.requireActiveServer()).thenThrow(new InvalidRequestException("No active MediaGarrd server selected"));

        mockMvc.perform(get("/api/client/backups"))
            .andExpect(status().isBadRequest())
            .andExpect(content().json(Fixtures.read("client/error-4xx.json"), JsonCompareMode.STRICT));
    }
}
