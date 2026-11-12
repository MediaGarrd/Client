package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.dto.VersionsDto;

class VersionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T18:00:00Z");
    private static final VersionStatusDto CLIENT = new VersionStatusDto("0.1.0", "0.1.0", "https://github.com/MediaGarrd/Client/releases/tag/v0.1.0", false, CheckState.OK, NOW, null);
    private static final VersionStatusDto SERVER = new VersionStatusDto("0.1.0", "0.2.0", "https://github.com/MediaGarrd/Server/releases/tag/v0.2.0", true, CheckState.OK, NOW, null);
    private static final String URL = "http://server:38471";

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
    private final ServerApiService serverApiService = mock(ServerApiService.class);
    private final VersionService service = new VersionService(updateCheckService, serverApiService, clock);

    VersionServiceTest() {
        when(updateCheckService.status()).thenReturn(CLIENT);
    }

    @Test
    void leavesTheServerOutUntilOneIsSelectedAndNeverCallsTheServerForIt() {
        assertNull(service.versions().server());
        assertFalse(service.needsRefresh());
        assertFalse(service.refresh());
        service.select(" ");

        assertNull(service.versions().server());
        verify(serverApiService, never()).serverVersion(anyString());
    }

    @Test
    void aSelectedServerIsPendingUntilItsVersionIsFetched() {
        assertTrue(service.select(URL));

        assertEquals(CheckState.NOT_CHECKED, service.versions().server().checkState());
        assertTrue(service.needsRefresh());
        assertFalse(service.select(URL));
    }

    @Test
    void cachesTheFetchedVersionWithoutAskingTheServerAgain() {
        when(serverApiService.serverVersion(URL)).thenReturn(SERVER);
        service.select(URL);

        assertTrue(service.refresh());

        assertEquals(new VersionsDto(CLIENT, SERVER), service.versions());
        assertEquals(new VersionsDto(CLIENT, SERVER), service.versions());
        assertFalse(service.needsRefresh());
        verify(serverApiService, times(1)).serverVersion(URL);
    }

    @Test
    void refetchesAtMostHourlyAndAtOnceAfterAnInvalidation() {
        when(serverApiService.serverVersion(URL)).thenReturn(SERVER);
        service.select(URL);
        service.refresh();

        now.set(NOW.plus(Duration.ofMinutes(59)));
        assertFalse(service.needsRefresh());
        now.set(NOW.plus(Duration.ofHours(1)));
        assertTrue(service.needsRefresh());

        service.refresh();
        assertFalse(service.needsRefresh());
        assertFalse(service.invalidate(false));
        assertFalse(service.needsRefresh());
        now.set(now.get().plus(Duration.ofMinutes(6)));
        assertTrue(service.invalidate(false));
        assertTrue(service.needsRefresh());
    }

    @Test
    void aServerThatHasNotCheckedYetIsAskedAgainAfterThirtySeconds() {
        VersionStatusDto notChecked = new VersionStatusDto("0.1.0", null, null, false, CheckState.NOT_CHECKED, null, null);
        when(serverApiService.serverVersion(URL)).thenReturn(notChecked).thenReturn(SERVER);
        service.select(URL);
        service.refresh();

        now.set(NOW.plus(Duration.ofSeconds(29)));
        assertFalse(service.needsRefresh());
        now.set(NOW.plus(Duration.ofSeconds(30)));
        assertTrue(service.needsRefresh());
        assertTrue(service.refresh());

        assertEquals(CheckState.OK, service.versions().server().checkState());
        now.set(NOW.plus(Duration.ofMinutes(10)));
        assertFalse(service.needsRefresh());
    }

    @Test
    void aServerThatReportedAnErrorIsAskedAgainAfterFiveMinutes() {
        VersionStatusDto failed = new VersionStatusDto("0.1.0", null, null, false, CheckState.ERROR, NOW, "GitHub is unreachable");
        when(serverApiService.serverVersion(URL)).thenReturn(failed);
        service.select(URL);
        service.refresh();

        now.set(NOW.plus(Duration.ofMinutes(4)));
        assertFalse(service.needsRefresh());
        now.set(NOW.plus(Duration.ofMinutes(5)));
        assertTrue(service.needsRefresh());
        assertTrue(service.invalidate(false));
    }

    @Test
    void aFailedFirstAttemptTurnsThePendingVersionIntoUnavailableOnce() {
        assertFalse(service.markUnreachable());
        service.select(URL);

        assertTrue(service.markUnreachable());
        assertEquals(CheckState.UNAVAILABLE, service.versions().server().checkState());
        assertFalse(service.markUnreachable());
        assertTrue(service.invalidate(false));
    }

    @Test
    void aFetchedVersionIsNotReplacedByAFailedLaterAttempt() {
        when(serverApiService.serverVersion(URL)).thenReturn(SERVER);
        service.select(URL);
        service.refresh();

        assertFalse(service.markUnreachable());
        assertEquals(SERVER, service.versions().server());
    }

    @Test
    void reportsWhetherARefetchChangedAnything() {
        when(serverApiService.serverVersion(URL)).thenReturn(SERVER);
        service.select(URL);

        assertTrue(service.refresh());
        assertFalse(service.refresh());
    }

    @Test
    void switchingServersDropsTheCachedVersionAndIgnoresAnInFlightAnswer() {
        when(serverApiService.serverVersion(URL)).thenAnswer(call -> {
            service.select("http://other:38471");
            return SERVER;
        });
        service.select(URL);

        assertFalse(service.refresh());

        assertEquals(CheckState.NOT_CHECKED, service.versions().server().checkState());
    }

    @Test
    void reportsTheServerAsUnavailableWhenItCannotBeReachedAndRetriesSooner() {
        when(serverApiService.serverVersion(URL)).thenThrow(new ResourceAccessException("refused"));
        service.select(URL);

        service.refresh();

        VersionsDto result = service.versions();
        assertEquals(CLIENT, result.client());
        assertEquals(CheckState.UNAVAILABLE, result.server().checkState());
        assertFalse(result.server().updateAvailable());
        now.set(NOW.plus(Duration.ofMinutes(9)));
        assertFalse(service.needsRefresh());
        now.set(NOW.plus(Duration.ofMinutes(10)));
        assertTrue(service.needsRefresh());
    }

    @Test
    void reportsAnOlderServerWithoutTheEndpointAsUnavailable() {
        when(serverApiService.serverVersion(URL))
            .thenThrow(new RestClientResponseException("Not Found", 404, "Not Found", null, null, null));
        service.select(URL);

        service.refresh();

        assertEquals(CheckState.UNAVAILABLE, service.versions().server().checkState());
    }

    @Test
    void reportsAnEmptyServerResponseAsUnavailable() {
        when(serverApiService.serverVersion(URL)).thenReturn(null);
        service.select(URL);

        service.refresh();

        assertEquals(CheckState.UNAVAILABLE, service.versions().server().checkState());
    }

    @Test
    void recoveringFromARealOutageRefetchesEvenWithinFiveMinutesOfAGoodAnswer() {
        when(serverApiService.serverVersion(URL)).thenReturn(SERVER);
        service.select(URL);
        service.refresh();

        assertFalse(service.invalidate(false));
        assertTrue(service.invalidate(true));
        assertTrue(service.needsRefresh());
    }
}
