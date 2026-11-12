package wellatleastitried.mediagarrdclient.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import wellatleastitried.mediagarrdclient.service.DashboardService;

@WebMvcTest(controllers = ClientEventsController.class)
@Timeout(20)
class ClientEventsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    DashboardService dashboard;

    @Test
    void opensAnUnbufferedEventStream() throws Exception {
        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.send(SseEmitter.event().name("link").data("{}"));
        when(dashboard.subscribe()).thenReturn(Optional.of(emitter));

        mockMvc.perform(get("/api/client/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(content().string("event:link\ndata:{}\n\n"));

        emitter.complete();
    }

    @Test
    void answers503WhenTooManyBrowsersAreSubscribed() throws Exception {
        when(dashboard.subscribe()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/client/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(""));
    }

    @Test
    void aBrowserThatWentAwayEndsTheStreamWithoutAnError() throws Exception {
        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.send(SseEmitter.event().name("link").data("{}"));
        when(dashboard.subscribe()).thenReturn(Optional.of(emitter));
        MvcResult started = mockMvc.perform(get("/api/client/events").accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andReturn();

        emitter.completeWithError(new IOException("Broken pipe"));

        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }
}
