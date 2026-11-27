package wellatleastitried.mediagarrdclient.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import wellatleastitried.mediagarrdclient.service.InvalidRequestException;

import wellatleastitried.mediagarrdclient.dto.PairingCodeDto;
import wellatleastitried.mediagarrdclient.dto.PairingResultDto;
import wellatleastitried.mediagarrdclient.dto.UnpairResultDto;
import wellatleastitried.mediagarrdclient.service.ClientPairingService;
import wellatleastitried.mediagarrdclient.service.PairingRequiredException;

@WebMvcTest(controllers = PairingController.class)
@Import(TestSession.class)
class PairingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ClientPairingService pairing;

    @Test
    void pairsAndNeverReturnsAToken() throws Exception {
        when(pairing.pair("123456", null)).thenReturn(new PairingResultDto(true, "MediaGarrd Client", null));

        mockMvc.perform(post("/api/client/pairing").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paired").value(true))
            .andExpect(jsonPath("$.name").value("MediaGarrd Client"))
            .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void passesOnWhyTheServerRefusedThePairing() throws Exception {
        when(pairing.pair("000000", null)).thenThrow(new InvalidRequestException("Invalid PIN"));

        mockMvc.perform(post("/api/client/pairing").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"000000\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid PIN"));
    }

    @Test
    void aRejectedTokenIsABadGatewayWithThePairingMarker() throws Exception {
        when(pairing.createCode()).thenThrow(new PairingRequiredException("The server rejected this client's token, pair it again"));

        mockMvc.perform(post("/api/client/pairing/code"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.pairing").value("required"))
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unpairsAndSaysWhetherTheServerWasTold() throws Exception {
        when(pairing.unpair()).thenReturn(new UnpairResultDto(false, "The server could not be told"));

        mockMvc.perform(delete("/api/client/pairing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serverRevoked").value(false))
            .andExpect(jsonPath("$.warning").value("The server could not be told"));
    }
}
