package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.dto.UnpairResultDto;

class ClientPairingServiceTest {

    private static final String SERVER = "http://server:38471";

    @TempDir
    Path tempDir;

    private ClientStateService state;
    private ServerApiService api;
    private ClientPairingService pairing;
    private final List<Long> pauses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MediaGarrdProperties configuration = new MediaGarrdProperties();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
        state = new ClientStateService(configuration, JsonMapper.builder().build());
        state.setActiveServer(SERVER);
        api = mock(ServerApiService.class);
        pauses.clear();
        pairing = new ClientPairingService(state, api, pauses::add);
    }

    @Test
    void storesTheTokenForTheActiveServerAndReturnsNoToken() {
        when(api.pair(SERVER, "123456", "MediaGarrd Client")).thenReturn(new StoredPairing("tok", "id1", "MediaGarrd Client"));

        var result = pairing.pair(" 123456 ", null);

        assertTrue(result.paired());
        assertEquals("tok", state.tokenFor(SERVER).orElseThrow());
        assertFalse(result.toString().contains("tok"));
    }

    @Test
    void refusesABlankCredentialWithoutCallingTheServer() {
        assertThrows(InvalidRequestException.class, () -> pairing.pair("  ", null));
        verify(api, never()).pair(anyString(), anyString(), anyString());
    }

    @Test
    void refusesAnOverlongCredentialOrNameWithoutCallingTheServer() {
        assertThrows(InvalidRequestException.class, () -> pairing.pair("1".repeat(ClientPairingService.MAX_CREDENTIAL_LENGTH + 1), null));
        assertThrows(InvalidRequestException.class, () -> pairing.pair("123456", "n".repeat(ClientPairingService.MAX_NAME_LENGTH + 1)));
        verify(api, never()).pair(anyString(), anyString(), anyString());
    }

    @Test
    void refusesToPairAgainWhileTheStoredTokenStillWorks() {
        state.storePairing(SERVER, new StoredPairing("old-tok", "old-id", "Home"));

        ConflictException e = assertThrows(ConflictException.class, () -> pairing.pair("123456", "Home"));

        assertEquals(ClientPairingService.ALREADY_PAIRED_MESSAGE, e.getMessage());
        verify(api).confirmPairing(SERVER, "old-tok");
        verify(api, never()).pair(anyString(), anyString(), anyString());
        assertEquals("old-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void pairsAgainWhenTheServerRefusesTheStoredToken() {
        state.storePairing(SERVER, new StoredPairing("old-tok", "old-id", "Home"));
        doThrow(new InvalidRequestException("no")).when(api).confirmPairing(SERVER, "old-tok");
        when(api.pair(SERVER, "123456", "Home")).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));

        var result = pairing.pair("123456", "Home");

        assertTrue(result.paired());
        assertNull(result.note());
        assertEquals("new-tok", state.tokenFor(SERVER).orElseThrow());
        verify(api, never()).revokeClient(anyString(), anyString());
    }

    @Test
    void refusesToPairWhenTheStoredTokenCannotBeProbed() {
        state.storePairing(SERVER, new StoredPairing("old-tok", "old-id", "Home"));
        doThrow(new ResourceAccessException("down")).when(api).confirmPairing(SERVER, "old-tok");

        assertThrows(ResourceAccessException.class, () -> pairing.pair("123456", "Home"));

        verify(api, never()).pair(anyString(), anyString(), anyString());
        assertEquals("old-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void aFailedNewPairingKeepsTheDeadStoredToken() {
        state.storePairing(SERVER, new StoredPairing("old-tok", "old-id", "Home"));
        doThrow(new InvalidRequestException("no")).when(api).confirmPairing(SERVER, "old-tok");
        when(api.pair(anyString(), anyString(), anyString())).thenThrow(new InvalidRequestException("Invalid PIN"));

        assertThrows(InvalidRequestException.class, () -> pairing.pair("000000", "Home"));

        assertEquals("old-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void unpairingWithoutAStoredIdOrPairingExplainsWhy() {
        assertEquals(ClientPairingService.NOT_PAIRED_WARNING, pairing.unpair().warning());
        state.storePairing(SERVER, new StoredPairing("tok", null, "Home"));

        UnpairResultDto result = pairing.unpair();

        assertFalse(result.serverRevoked());
        assertEquals(ClientPairingService.NO_ID_WARNING, result.warning());
    }

    @Test
    void aFailedPairingStoresNothing() {
        when(api.pair(anyString(), anyString(), anyString())).thenThrow(new ResourceAccessException("down"));

        assertThrows(ResourceAccessException.class, () -> pairing.pair("123456", "Home"));

        assertFalse(state.hasPairing(SERVER));
    }

    @Test
    void unpairingRevokesOnTheServerAndForgetsTheToken() {
        state.storePairing(SERVER, new StoredPairing("tok", "id1", "Home"));

        UnpairResultDto result = pairing.unpair();

        assertTrue(result.serverRevoked());
        assertNull(result.warning());
        verify(api).revokeClient(SERVER, "id1");
        assertFalse(state.hasPairing(SERVER));
    }

    @Test
    void unpairingForgetsTheTokenEvenWhenTheServerIsUnreachable() {
        state.storePairing(SERVER, new StoredPairing("tok", "id1", "Home"));
        doThrow(new ResourceAccessException("down")).when(api).revokeClient(SERVER, "id1");

        UnpairResultDto result = pairing.unpair();

        assertFalse(result.serverRevoked());
        assertEquals(ClientPairingService.UNREVOKED_WARNING, result.warning());
        assertFalse(state.hasPairing(SERVER));
    }

    @Test
    void unpairingForgetsTheTokenEvenWhenTheServerRejectsIt() {
        state.storePairing(SERVER, new StoredPairing("tok", "id1", "Home"));
        doThrow(new PairingRequiredException("rejected")).when(api).revokeClient(SERVER, "id1");

        assertFalse(pairing.unpair().serverRevoked());
        assertFalse(state.hasPairing(SERVER));
    }

    @Test
    void storesThePairingBeforeConfirmingIt() {
        when(api.pair(SERVER, "123456", "MediaGarrd Client")).thenReturn(new StoredPairing("tok", "id1", "MediaGarrd Client"));
        doAnswer(call -> {
            assertEquals("tok", state.tokenFor(SERVER).orElseThrow());
            return null;
        }).when(api).confirmPairing(SERVER, "tok");

        var result = pairing.pair("123456", null);

        assertTrue(result.paired());
        assertNull(result.note());
        verify(api).confirmPairing(SERVER, "tok");
        assertEquals("tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void aRefusedConfirmationForgetsTheNewPairing() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new InvalidRequestException("The server did not accept this pairing")).when(api).confirmPairing(SERVER, "new-tok");

        assertThrows(InvalidRequestException.class, () -> pairing.pair("123456", "Home"));

        assertFalse(state.hasPairing(SERVER));
        verify(api, times(1)).confirmPairing(SERVER, "new-tok");
        assertTrue(pauses.isEmpty());
    }

    @Test
    void aPublicOnlyConfirmationForgetsTheNewPairing() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new ConfirmationRefusedException("not a confirmation")).when(api).confirmPairing(SERVER, "new-tok");

        assertThrows(ConfirmationRefusedException.class, () -> pairing.pair("123456", "Home"));

        assertFalse(state.hasPairing(SERVER));
    }

    @Test
    void aRefusedConfirmationDoesNotForgetANewerPairingStoredMeanwhile() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doAnswer(call -> {
            state.storePairing(SERVER, new StoredPairing("newer-tok", "newer-id", "Home"));
            throw new InvalidRequestException("no");
        }).when(api).confirmPairing(SERVER, "new-tok");

        assertThrows(InvalidRequestException.class, () -> pairing.pair("123456", "Home"));

        assertEquals("newer-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void aServerErrorOnConfirmationKeepsTheTokenAndReturnsTheNote() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new UpstreamResponseException("status 500")).when(api).confirmPairing(SERVER, "new-tok");

        var result = pairing.pair("123456", "Home");

        assertTrue(result.paired());
        assertEquals(ClientPairingService.DEFERRED_CONFIRMATION_NOTE, result.note());
        assertEquals("new-tok", state.tokenFor(SERVER).orElseThrow());
        verify(api, times(1)).confirmPairing(SERVER, "new-tok");
    }

    @Test
    void aRedirectOrAnInterruptedRetryOnConfirmationKeepsTheToken() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new ServerRedirectException(302, "http://elsewhere")).when(api).confirmPairing(SERVER, "new-tok");

        assertEquals(ClientPairingService.DEFERRED_CONFIRMATION_NOTE, pairing.pair("123456", "Home").note());
        assertEquals("new-tok", state.tokenFor(SERVER).orElseThrow());

        state.forgetPairing(SERVER);
        doThrow(new ResourceAccessException("down")).when(api).confirmPairing(SERVER, "new-tok");
        ClientPairingService interrupted = new ClientPairingService(state, api, millis -> {
            throw new UpstreamResponseException("interrupted");
        });

        assertEquals(ClientPairingService.DEFERRED_CONFIRMATION_NOTE, interrupted.pair("123456", "Home").note());
        assertEquals("new-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void retriesTheConfirmationAfterAGatewayErrorAndStoresTheToken() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new ConfirmationUnavailableException("status 502")).doThrow(new ConfirmationUnavailableException("status 504")).doNothing()
            .when(api).confirmPairing(SERVER, "new-tok");

        var result = pairing.pair("123456", "Home");

        assertNull(result.note());
        verify(api, times(3)).confirmPairing(SERVER, "new-tok");
        assertEquals(List.of(250L, 500L), pauses);
        assertEquals("new-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void retriesTheConfirmationAfterANetworkErrorWithBackoff() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new ResourceAccessException("reset")).doThrow(new ResourceAccessException("reset")).doNothing()
            .when(api).confirmPairing(SERVER, "new-tok");

        assertNull(pairing.pair("123456", "Home").note());

        verify(api, times(3)).confirmPairing(SERVER, "new-tok");
        assertEquals(List.of(250L, 500L), pauses);
        assertEquals("new-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void givesUpOnTheConfirmationAfterThreeRetriesButKeepsTheTokenAndReturnsTheNote() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new ResourceAccessException("down")).when(api).confirmPairing(SERVER, "new-tok");

        var result = pairing.pair("123456", "Home");

        assertTrue(result.paired());
        assertEquals(ClientPairingService.DEFERRED_CONFIRMATION_NOTE, result.note());
        verify(api, times(4)).confirmPairing(SERVER, "new-tok");
        assertEquals(List.of(250L, 500L, 1000L), pauses);
        assertEquals("new-tok", state.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void aDeferredPairingSurvivesARestartOfTheState() {
        when(api.pair(anyString(), anyString(), anyString())).thenReturn(new StoredPairing("new-tok", "new-id", "Home"));
        doThrow(new ResourceAccessException("down")).when(api).confirmPairing(SERVER, "new-tok");
        pairing.pair("123456", "Home");

        MediaGarrdProperties configuration = new MediaGarrdProperties();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
        ClientStateService reloaded = new ClientStateService(configuration, JsonMapper.builder().build());

        assertEquals("new-tok", reloaded.tokenFor(SERVER).orElseThrow());
    }

    @Test
    void concurrentPairingsRunOneAfterTheOther() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        when(api.pair(anyString(), anyString(), anyString())).thenAnswer(call -> {
            if (inside.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
            }
            Thread.sleep(100);
            inside.decrementAndGet();
            return new StoredPairing("tok", "id", "Home");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> pairing.pair("123456", "Home"));
            Future<?> second = pool.submit(() -> pairing.pair("123456", "Home"));
            int successes = 0;
            int conflicts = 0;
            for (Future<?> f : List.of(first, second)) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof ConflictException, e.getCause().toString());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, overlaps.get());
        verify(api, times(1)).pair(anyString(), anyString(), anyString());
    }
}
