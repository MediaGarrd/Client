package wellatleastitried.mediagarrdclient.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;
import wellatleastitried.mediagarrdclient.service.BackupPickupService;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;
import wellatleastitried.mediagarrdclient.service.ServerDiscoveryService;

@WebMvcTest(controllers = ClientController.class)
class ClientControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ClientStateService stateService;

    @MockBean
    ServerDiscoveryService discoveryService;

    @MockBean
    ServerApiService serverApiService;

    @MockBean
    BackupPickupService backupPickupService;

    @Test
    void statusEndpointReturnsState() throws Exception {
        when(stateService.getActiveServer()).thenReturn("http://server:8080");
        when(stateService.getPickupInterval()).thenReturn(Duration.ofMinutes(45));
        when(stateService.getKnownServers()).thenReturn(List.of("http://server:8080"));

        mockMvc.perform(get("/api/client/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeServer").value("http://server:8080"));
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
    void listBackupsReturnsServerData() throws Exception {
        when(stateService.requireActiveServer()).thenReturn("http://server:8080");
        when(serverApiService.listBackups("http://server:8080")).thenReturn(List.of(new BackupDto("id", "file.zip", 10L, null)));

        mockMvc.perform(get("/api/client/backups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("id"));
    }

    @Test
    void setServerEndpointAcceptsUpdate() throws Exception {
        when(stateService.getKnownServers()).thenReturn(List.of("http://server:8080"));
        when(stateService.getPickupInterval()).thenReturn(Duration.ofMinutes(30));
        when(stateService.getActiveServer()).thenReturn("http://server:8080");

        mockMvc.perform(put("/api/client/server")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serverUrl\":\"http://server:8080\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void pickupEndpointStartsTask() throws Exception {
        when(backupPickupService.startPickupLatestTask()).thenReturn(new PickupStartDto("task-1", "id", "file.zip", 10L));

        mockMvc.perform(post("/api/client/pickup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value("task-1"))
            .andExpect(jsonPath("$.backupId").value("id"));
    }

    @Test
    void statusConnectsThePreferredServerFirst() throws Exception {
        mockMvc.perform(get("/api/client/status")).andExpect(status().isOk());

        verify(discoveryService).connectPreferredServer();
    }

    @Test
    void normalizesSelectedServerUrls() throws Exception {
        mockMvc.perform(json(put("/api/client/server"), "{\"serverUrl\":\" http://server:8080/ \"}"))
            .andExpect(status().isOk());

        verify(stateService).setActiveServer("http://server:8080");
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
        when(backupPickupService.updatePickupInterval(Duration.ZERO)).thenThrow(new IllegalArgumentException("Pickup interval must be a positive duration"));

        mockMvc.perform(json(put("/api/client/pickup-interval"), "{\"interval\":\"PT0S\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Pickup interval must be a positive duration"));
    }

    @Test
    void unknownPickupTasksAreNotFound() throws Exception {
        when(backupPickupService.pickupProgress("gone")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Pickup task not found: gone"));

        mockMvc.perform(get("/api/client/pickup/progress/gone"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Pickup task not found: gone"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
