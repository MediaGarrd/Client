package wellatleastitried.mediagarrdclient.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import wellatleastitried.mediagarrdclient.service.InvalidRequestException;
import wellatleastitried.mediagarrdclient.service.NotFoundException;

import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.DashboardState;
import wellatleastitried.mediagarrdclient.dto.HealthSummary;
import wellatleastitried.mediagarrdclient.dto.LinkState;
import wellatleastitried.mediagarrdclient.dto.PairingState;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;
import wellatleastitried.mediagarrdclient.dto.VersionsDto;
import wellatleastitried.mediagarrdclient.service.BackupPickupService;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.DashboardService;
import wellatleastitried.mediagarrdclient.service.BackupRunFailedException;
import wellatleastitried.mediagarrdclient.service.BackupRunTimeoutException;
import wellatleastitried.mediagarrdclient.service.ServerApiService;
import wellatleastitried.mediagarrdclient.service.ServerDiscoveryService;
import wellatleastitried.mediagarrdclient.service.ServerSelectionService;

@WebMvcTest(controllers = ClientController.class)
@Import({TestSession.class, ServerSelectionService.class})
class ClientControllerTest {

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

    @Test
    void mapsABackupRunTimeoutToAGatewayTimeoutWithAnErrorBody() throws Exception {
        when(stateService.requireActiveServer()).thenReturn("http://server:8080");
        doThrow(new BackupRunTimeoutException("The backup is probably still running on the server.", null))
            .when(serverApiService).triggerBackup("http://server:8080");

        mockMvc.perform(post("/api/client/backups/run"))
            .andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.error").value("The backup is probably still running on the server."));
    }

    @Test
    void mapsAFailedBackupRunToAnInternalErrorWithTheReason() throws Exception {
        when(stateService.requireActiveServer()).thenReturn("http://server:8080");
        doThrow(new BackupRunFailedException("Backup of radarr failed: disk is full", null))
            .when(serverApiService).triggerBackup("http://server:8080");

        mockMvc.perform(post("/api/client/backups/run"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("Backup of radarr failed: disk is full"));
    }

    @Test
    void discoverEndpointReturnsOnlyTheServersFoundByTheScan() throws Exception {
        when(discoveryService.discover()).thenReturn(List.of("http://server:8080"));
        when(stateService.getKnownServers()).thenReturn(List.of("http://server:8080", "http://active:38471"));

        mockMvc.perform(post("/api/client/discover"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0]").value("http://server:8080"));

        verify(stateService).updateKnownServers(List.of("http://server:8080"));
    }

    @Test
    void statusHasNoNetworkSideEffect() throws Exception {
        mockMvc.perform(get("/api/client/status")).andExpect(status().isOk());

        verifyNoInteractions(discoveryService, serverApiService);
    }

    @Test
    void dashboardEndpointReturnsTheStoredStateWithoutContactingTheServer() throws Exception {
        when(dashboardService.state()).thenReturn(new DashboardState(
            "http://server:8080",
            List.of("http://server:8080"),
            LinkState.DEGRADED,
            true,
            new HealthSummary(Duration.ofHours(6), 5),
            new VersionsDto(null, null),
            PairingState.PAIRED));

        mockMvc.perform(get("/api/client/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeServer").value("http://server:8080"))
            .andExpect(jsonPath("$.knownServers[0]").value("http://server:8080"))
            .andExpect(jsonPath("$.link").value("DEGRADED"))
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.health.backupRetentionCount").value(5))
            .andExpect(jsonPath("$.versions.server").doesNotExist());

        verifyNoInteractions(serverApiService, discoveryService);
    }

    @Test
    void normalizesSelectedServerUrls() throws Exception {
        when(discoveryService.isMediaGarrdServer("http://server:8080")).thenReturn(true);
        mockMvc.perform(json(put("/api/client/server"), "{\"serverUrl\":\" http://server:8080/ \"}"))
            .andExpect(status().isOk());

        verify(stateService).setActiveServer("http://server:8080");
    }

    @Test
    void refusesAServerUrlThatIsNotAMediaGarrdServer() throws Exception {
        when(discoveryService.isMediaGarrdServer("http://intranet-printer:631")).thenReturn(false);

        mockMvc.perform(json(put("/api/client/server"), "{\"serverUrl\":\"http://intranet-printer:631\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("No MediaGarrd server found at http://intranet-printer:631"));

        verify(stateService, never()).setActiveServer(any());
    }

    @Test
    void doesNotProbeAServerThatIsAlreadyKnown() throws Exception {
        when(stateService.isKnownServer("http://server:8080")).thenReturn(true);

        mockMvc.perform(json(put("/api/client/server"), "{\"serverUrl\":\"http://server:8080\"}"))
            .andExpect(status().isOk());

        verify(stateService).setActiveServer("http://server:8080");
        verify(discoveryService, never()).isMediaGarrdServer(any(String.class));
    }

    @Test
    void rejectsInvalidServerSelections() throws Exception {
        mockMvc.perform(json(put("/api/client/server"), "{\"serverUrl\":\"ftp://server\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("serverUrl must be an http:// or https:// URL: ftp://server"));
        mockMvc.perform(json(put("/api/client/server"), "{\"serverIp\":\"10.0.0.2\",\"serverPort\":70000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("serverPort must be between 1 and 65535"));
        mockMvc.perform(json(put("/api/client/server"), "{}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/api/client/server"), "not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Request body is missing or malformed"));

        verify(stateService, never()).setActiveServer(any());
    }

    @Test
    void reportsWhenNoServerAnswersAtTheGivenAddress() throws Exception {
        when(discoveryService.resolveServerByIp("10.0.0.2", null)).thenReturn(Optional.empty());

        mockMvc.perform(json(put("/api/client/server"), "{\"serverIp\":\"10.0.0.2\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("No MediaGarrd server found at 10.0.0.2"));
    }

    @Test
    void rejectsInvalidPickupIntervals() throws Exception {
        when(backupPickupService.updatePickupInterval(Duration.ZERO)).thenThrow(new InvalidRequestException("Pickup interval must be a positive duration"));

        mockMvc.perform(json(put("/api/client/pickup-interval"), "{\"interval\":\"PT0S\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Pickup interval must be a positive duration"));
    }

    @Test
    void pickingUpASpecificBackupStartsATaskForThatId() throws Exception {
        when(backupPickupService.startPickupByIdTask("b1")).thenReturn(new PickupStartDto("task-1", "b1", "b1-media-backup.zip", 10L));

        mockMvc.perform(post("/api/client/pickup/b1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value("task-1"))
            .andExpect(jsonPath("$.backupId").value("b1"))
            .andExpect(jsonPath("$.fileName").value("b1-media-backup.zip"));

        verify(backupPickupService).startPickupByIdTask("b1");
        verify(backupPickupService, never()).startPickupLatestTask();
    }

    @Test
    void pickingUpAnUnknownBackupIsNotFound() throws Exception {
        when(backupPickupService.startPickupByIdTask("gone")).thenThrow(new NotFoundException("Backup not found: gone"));

        mockMvc.perform(post("/api/client/pickup/gone"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Backup not found: gone"));
    }

    @Test
    void unknownPickupTasksAreNotFound() throws Exception {
        when(backupPickupService.pickupProgress("gone")).thenThrow(new NotFoundException("Pickup task not found: gone"));

        mockMvc.perform(get("/api/client/pickup/progress/gone"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Pickup task not found: gone"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
