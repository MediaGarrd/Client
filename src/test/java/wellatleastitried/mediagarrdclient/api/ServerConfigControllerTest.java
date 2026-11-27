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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import wellatleastitried.mediagarrdclient.service.InvalidRequestException;

import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.service.ClientStateService;
import wellatleastitried.mediagarrdclient.service.ServerApiService;

@WebMvcTest(controllers = ServerConfigController.class)
@Import(TestSession.class)
class ServerConfigControllerTest {

    private static final String SERVER = "http://server:8080";
    private static final ServiceDto RADARR = new ServiceDto("radarr", List.of("/srv/radarr"));
    private static final String RADARR_JSON = """
        {"name": "radarr", "paths": ["/srv/radarr"]}
        """;

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
    void leavesScheduleAloneWhenIntervalIsOmitted() throws Exception {
        when(serverApiService.updateServerConfig(SERVER, null, 5)).thenReturn(new ServerConfigDto("/backups", 5, Duration.ofHours(6)));

        mockMvc.perform(json(put("/api/client/server-config"), "{\"retentionCount\": 5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backupInterval").value("PT6H"));

        verify(serverApiService, never()).updateServerSchedule(anyString(), any());
    }

    @Test
    void refusesAnOutOfRangeIntervalBeforeChangingAnything() throws Exception {
        for (String interval : List.of("\"PT14M59S\"", "\"PT0S\"", "\"-PT1H\"", "\"PT8761H\"", "899", "31536001")) {
            mockMvc.perform(json(put("/api/client/server-config"),
                    "{\"backupLocation\": \"/backups\", \"retentionCount\": 5, \"backupInterval\": " + interval + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Backup interval must be between 15 minutes and 365 days"));
        }

        verify(serverApiService, never()).updateServerConfig(anyString(), any(), any());
        verify(serverApiService, never()).updateServerSchedule(anyString(), any());
    }

    @Test
    void acceptsTheIntervalBounds() throws Exception {
        when(serverApiService.updateServerConfig(SERVER, "/backups", 5)).thenReturn(new ServerConfigDto("/backups", 5, Duration.ofHours(6)));
        for (String interval : List.of("\"PT15M\"", "\"PT8760H\"", "900", "31536000")) {
            when(serverApiService.updateServerSchedule(anyString(), any())).thenReturn(new ServerStatusDto(true, false, 5, Duration.ofMinutes(15)));
            mockMvc.perform(json(put("/api/client/server-config"),
                    "{\"backupLocation\": \"/backups\", \"retentionCount\": 5, \"backupInterval\": " + interval + "}"))
                .andExpect(status().isOk());
        }
    }

    @Test
    void forwardsServiceChanges() throws Exception {
        when(serverApiService.listServices(SERVER)).thenReturn(List.of(RADARR));
        when(serverApiService.addService(SERVER, RADARR)).thenReturn(RADARR);
        when(serverApiService.updateService(SERVER, "radarr-4k", RADARR)).thenReturn(RADARR);

        mockMvc.perform(get("/api/client/services"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("radarr"))
            .andExpect(jsonPath("$[0].paths[0]").value("/srv/radarr"));
        mockMvc.perform(json(post("/api/client/services"), RADARR_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("radarr"));
        mockMvc.perform(json(put("/api/client/services/radarr-4k"), RADARR_JSON))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/client/services/radarr"))
            .andExpect(status().isNoContent());

        verify(serverApiService).removeService(SERVER, "radarr");
    }

    @Test
    void refusesServiceNamesThatCouldChangeThePath() throws Exception {
        mockMvc.perform(json(put("/api/client/services/%2E%2E"), RADARR_JSON))
            .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/api/client/services/a%2Fb"), RADARR_JSON))
            .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/client/services/..%5Cetc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());

        verify(serverApiService, never()).updateService(anyString(), anyString(), any());
        verify(serverApiService, never()).removeService(anyString(), anyString());
    }

    @Test
    void routineReadsDoNotLogAtInfoButWritesDo() throws Exception {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            when(serverApiService.serverConfig(SERVER)).thenReturn(new ServerConfigDto("/backups", 5, Duration.ofHours(6)));
            when(serverApiService.listServices(SERVER)).thenReturn(List.of(RADARR));
            mockMvc.perform(get("/api/client/server-config")).andExpect(status().isOk());
            mockMvc.perform(get("/api/client/services")).andExpect(status().isOk());
            long infoAfterReads = logs.list.stream().filter(event -> event.getLevel().toInt() >= ch.qos.logback.classic.Level.INFO_INT && event.getLoggerName().endsWith("ServerConfigController")).count();

            when(serverApiService.addService(SERVER, RADARR)).thenReturn(RADARR);
            mockMvc.perform(json(post("/api/client/services"), RADARR_JSON)).andExpect(status().isCreated());
            long infoAfterWrite = logs.list.stream().filter(event -> event.getLevel().toInt() >= ch.qos.logback.classic.Level.INFO_INT && event.getLoggerName().endsWith("ServerConfigController")).count();

            org.junit.jupiter.api.Assertions.assertEquals(0, infoAfterReads);
            org.junit.jupiter.api.Assertions.assertEquals(1, infoAfterWrite);
        } finally {
            root.detachAppender(logs);
        }
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
