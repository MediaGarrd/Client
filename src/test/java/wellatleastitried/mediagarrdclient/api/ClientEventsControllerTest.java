package wellatleastitried.mediagarrdclient.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import wellatleastitried.mediagarrdclient.service.ClientEvent;
import wellatleastitried.mediagarrdclient.service.DashboardService;

@WebMvcTest(controllers = ClientEventsController.class)
@Import(TestSession.class)
@Timeout(20)
class ClientEventsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DashboardService dashboard;

    @MockitoBean
    ClientEventBroadcaster broadcaster;

    @BeforeEach
    void dashboardRegistersTheSubscriber() {
        when(dashboard.subscribe(any())).thenAnswer(invocation -> invocation.<Function<List<ClientEvent>, Optional<SseEmitter>>>getArgument(0).apply(List.of()));
    }

    @Test
    void opensAnUnbufferedEventStream() throws Exception {
        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.send(SseEmitter.event().name("link").data("{}"));
        when(broadcaster.subscribe(any(), any())).thenReturn(Optional.of(emitter));

        mockMvc.perform(get("/api/client/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andExpect(status().isOk())
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(content().string("event:link\ndata:{}\n\n"));

        emitter.complete();
    }

    @Test
    void answers503WhenTooManyBrowsersAreSubscribed() throws Exception {
        when(broadcaster.subscribe(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/client/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(""));
    }

    @Test
    void aBrowserThatWentAwayEndsTheStreamWithoutAnError() throws Exception {
        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.send(SseEmitter.event().name("link").data("{}"));
        when(broadcaster.subscribe(any(), any())).thenReturn(Optional.of(emitter));
        MvcResult started = mockMvc.perform(get("/api/client/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andReturn();

        emitter.completeWithError(new IOException("Broken pipe"));

        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }
}
