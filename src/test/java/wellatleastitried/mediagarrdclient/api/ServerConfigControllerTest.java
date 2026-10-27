package wellatleastitried.mediagarrdclient.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;

@WebMvcTest(controllers = ServerConfigController.class)
class ServerConfigControllerTest {

    private static final String SERVER = "http://server:8080";
    private static final ServiceDto RADARR = new ServiceDto("radarr", List.of("/srv/radarr"));
    private static final String RADARR_JSON = """
        {"name": "radarr", "paths": ["/srv/radarr"]}
        """;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ClientStateService stateService;

    @MockBean
    ServerApiService serverApiService;

    @BeforeEach
    void setUp() {
        when(stateService.requireActiveServer()).thenReturn(SERVER);
    }

    @Test
    void returnsServerConfig() throws Exception {
        when(serverApiService.serverConfig(SERVER)).thenReturn(new ServerConfigDto("/backups", 4, Duration.ofHours(6)));

        mockMvc.perform(get("/api/client/server-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backupLocation").value("/backups"))
            .andExpect(jsonPath("$.retentionCount").value(4))
            .andExpect(jsonPath("$.backupInterval").value("PT6H"));
    }

    @Test
    void updatesConfigAndScheduleTogether() throws Exception {
        when(serverApiService.updateServerConfig(SERVER, "/new", 3)).thenReturn(new ServerConfigDto("/new", 3, Duration.ofHours(6)));
        when(serverApiService.updateServerSchedule(SERVER, Duration.ofMinutes(90)))
            .thenReturn(new ServerStatusDto(true, false, 3, Duration.ofMinutes(90)));

        mockMvc.perform(json(put("/api/client/server-config"), "{\"backupLocation\": \"/new\", \"retentionCount\": 3, \"backupInterval\": \"PT90M\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backupLocation").value("/new"))
            .andExpect(jsonPath("$.retentionCount").value(3))
            .andExpect(jsonPath("$.backupInterval").value("PT1H30M"));
    }

    @Test
    void leavesScheduleAloneWhenIntervalIsOmitted() throws Exception {
        when(serverApiService.updateServerConfig(SERVER, null, 5)).thenReturn(new ServerConfigDto("/backups", 5, Duration.ofHours(6)));

        mockMvc.perform(json(put("/api/client/server-config"), "{\"retentionCount\": 5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backupInterval").value("PT6H"));

        verify(serverApiService, never()).updateServerSchedule(anyString(), any());
    }

    @Test
    void forwardsServiceChanges() throws Exception {
        when(serverApiService.listServices(SERVER)).thenReturn(List.of(RADARR));
        when(serverApiService.addService(SERVER, RADARR)).thenReturn(RADARR);
        when(serverApiService.updateService(SERVER, "old name", RADARR)).thenReturn(RADARR);

        mockMvc.perform(get("/api/client/services"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("radarr"))
            .andExpect(jsonPath("$[0].paths[0]").value("/srv/radarr"));
        mockMvc.perform(json(post("/api/client/services"), RADARR_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("radarr"));
        mockMvc.perform(json(put("/api/client/services/old name"), RADARR_JSON))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/client/services/radarr"))
            .andExpect(status().isNoContent());

        verify(serverApiService).removeService(SERVER, "radarr");
    }

    @Test
    void rejectsRequestsWithoutAnActiveServer() throws Exception {
        when(stateService.requireActiveServer())
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active MediaGarrd server selected"));

        mockMvc.perform(get("/api/client/services"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("No active MediaGarrd server selected"));

        verify(serverApiService, never()).listServices(anyString());
    }

    @Test
    void reportsAnUnreachableServer() throws Exception {
        when(serverApiService.serverConfig(SERVER)).thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get("/api/client/server-config"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("MediaGarrd server is unreachable"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
