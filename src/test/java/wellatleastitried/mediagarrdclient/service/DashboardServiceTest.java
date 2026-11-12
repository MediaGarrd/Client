package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.DashboardState;
import wellatleastitried.mediagarrdclient.dto.LinkState;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.service.ClientEventBroadcasterTest.RecordingEmitter;

@Timeout(30)
class DashboardServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .build();
    private static final Instant START = Instant.parse("2026-11-12T10:00:00Z");
    private static final String A = "http://a:38471";
    private static final String B = "http://b:38471";
    private static final ServerStatusDto IDLE = new ServerStatusDto(true, false, 5, Duration.ofHours(6));
    private static final ServerStatusDto BUSY = new ServerStatusDto(true, true, 5, Duration.ofHours(6));
    private static final VersionStatusDto SERVER_VERSION = new VersionStatusDto("0.1.0", "0.2.0", "https://example.test/r", true, CheckState.OK, START, null);
    private static final VersionStatusDto CLIENT_VERSION = new VersionStatusDto("0.1.0", "0.1.0", null, false, CheckState.OK, START, null);

    @TempDir
    Path tempDir;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
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
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final List<ClientEventBroadcaster> broadcasters = new ArrayList<>();
    private final ServerApiService serverApi = mock(ServerApiService.class);
    private final UpdateCheckService updateCheck = mock(UpdateCheckService.class);
    private ClientStateService state;
    private ClientEventBroadcaster broadcaster;
    private DashboardService dashboard;

    @BeforeEach
    void setUp() {
        Configuration configuration = new Configuration();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
        state = new ClientStateService(configuration, MAPPER);
        when(updateCheck.status()).thenReturn(CLIENT_VERSION);
        when(serverApi.health(anyString())).thenReturn(IDLE);
        when(serverApi.serverVersion(anyString())).thenReturn(SERVER_VERSION);
    }

    private void create(Executor executor) {
        broadcaster = new ClientEventBroadcaster(Duration.ofMinutes(10), 8, 64, Duration.ofMinutes(5), Duration.ofMinutes(6), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }, RecordingEmitter::new);
        broadcasters.add(broadcaster);
        dashboard = new DashboardService(state, broadcaster, serverApi, new VersionService(updateCheck, serverApi, clock), updateCheck, MAPPER, executor, clock);
        dashboard.start();
    }

    private void create() {
        create(Runnable::run);
    }

    @AfterEach
    void tearDown() {
        broadcasters.forEach(ClientEventBroadcaster::shutdown);
    }

    private RecordingEmitter subscribe() {
        return (RecordingEmitter) dashboard.subscribe().orElseThrow();
    }

    private static List<String> drain(RecordingEmitter emitter) throws InterruptedException {
        List<String> events = new ArrayList<>();
        String next;
        while ((next = emitter.sent.poll(150, TimeUnit.MILLISECONDS)) != null) {
            events.add(next);
        }
        return events;
    }

    private static String nameOf(String event) {
        for (String line : event.split("\n")) {
            if (line.startsWith("event:")) {
                return line.substring("event:".length());
            }
        }
        return "";
    }

    private static List<String> names(List<String> events) {
        return events.stream().map(DashboardServiceTest::nameOf).toList();
    }

    private void advance(Duration duration) {
        now.set(now.get().plus(duration));
    }

    @Test
    void startsDownWithNothingKnownAndNoServer() {
        create();

        DashboardState result = dashboard.state();

        assertEquals(null, result.activeServer());
        assertEquals(List.of(), result.knownServers());
        assertEquals(LinkState.DOWN, result.link());
        assertEquals(null, result.running());
        assertEquals(null, result.health());
        assertEquals(null, result.versions().server());
        assertEquals(CLIENT_VERSION, result.versions().client());
        verify(serverApi, never()).health(anyString());
    }

    @Test
    void aFirstSubscriberGetsOneFullSnapshotThatReflectsEverythingTheModelHolds() throws Exception {
        state.setActiveServer(A);
        create();

        RecordingEmitter browser = subscribe();

        String snapshot = browser.next();
        assertEquals("snapshot", nameOf(snapshot));
        assertTrue(snapshot.contains("\"activeServer\":\"" + A + "\""), snapshot);
        assertTrue(snapshot.contains("\"knownServers\":[\"" + A + "\"]"), snapshot);
        assertTrue(snapshot.contains("\"link\":\"DEGRADED\""), snapshot);
        assertTrue(snapshot.contains("\"running\":false"), snapshot);
        assertTrue(snapshot.contains("\"health\":{\"backupInterval\":\"PT6H\",\"backupRetentionCount\":5}"), snapshot);
        assertTrue(snapshot.contains("\"versions\":{\"client\":{"), snapshot);
        assertTrue(snapshot.contains("\"latestVersion\":\"0.2.0\""), snapshot);
        assertTrue(drain(browser).isEmpty());
    }

    @Test
    void aLateSubscriberGetsTheLatestSnapshotFollowedByGranularEvents() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        dashboard.runEvent(A, "run-started", "{\"running\":true,\"startedAt\":\"t\"}", true);

        RecordingEmitter browser = subscribe();
        dashboard.runEvent(A, "run-finished", "{\"running\":false,\"status\":\"COMPLETED\"}", false);

        List<String> events = drain(browser);
        assertEquals(List.of("snapshot", "run-finished"), names(events));
        assertTrue(events.get(0).contains("\"link\":\"LIVE\"") && events.get(0).contains("\"running\":true"), events.get(0));
        assertEquals("event:run-finished\ndata:{\"running\":false,\"status\":\"COMPLETED\"}\n\n", events.get(1));
    }

    @Test
    void theLinkIsLiveWhileTheStreamIsUpDegradedWhenOnlyHealthAnswersAndDownOtherwise() throws Exception {
        state.setActiveServer(A);
        doThrow(new ResourceAccessException("refused")).when(serverApi).health(A);
        create();
        assertEquals(LinkState.DOWN, dashboard.state().link());

        doReturn(IDLE).when(serverApi).health(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        assertEquals(LinkState.DEGRADED, dashboard.state().link());

        dashboard.streamUp(A);
        assertEquals(LinkState.LIVE, dashboard.state().link());

        dashboard.streamDown(A);
        assertEquals(LinkState.DEGRADED, dashboard.state().link());

        doThrow(new ResourceAccessException("refused")).when(serverApi).health(A);
        dashboard.runEvent(A, "state", "{\"running\":true}", true);
        dashboard.streamUp(A);
        dashboard.streamDown(A);
        DashboardState down = dashboard.state();
        assertEquals(LinkState.DOWN, down.link());
        assertEquals(null, down.running());
    }

    @Test
    void linkChangesArePushedOnlyWhenTheStateChanges() throws Exception {
        state.setActiveServer(A);
        create();
        RecordingEmitter browser = subscribe();
        browser.next();

        dashboard.streamUp(A);
        dashboard.streamUp(A);
        dashboard.streamDown(A);

        List<String> links = drain(browser).stream().filter(event -> nameOf(event).equals("link")).toList();
        assertEquals(3, links.size());
        assertTrue(links.get(0).contains("\"state\":\"LIVE\"") && links.get(0).contains("\"server\":\"" + A + "\""));
        assertTrue(links.get(1).contains("\"state\":\"CONNECTING\""));
        assertTrue(links.get(2).contains("\"state\":\"DEGRADED\""));
    }

    @Test
    void pollsHealthEverySevenToEightSecondsWhileNotLiveAndFeedsTheState() throws Exception {
        state.setActiveServer(A);
        create();
        RecordingEmitter browser = subscribe();
        browser.next();
        clearInvocations(serverApi);

        advance(Duration.ofSeconds(3));
        dashboard.tick();
        verify(serverApi, never()).health(anyString());

        doReturn(BUSY).when(serverApi).health(A);
        advance(Duration.ofSeconds(5));
        dashboard.tick();
        verify(serverApi, times(1)).health(A);
        assertEquals(true, dashboard.state().running());
        assertEquals(List.of("state"), names(drain(browser)));

        doReturn(IDLE).when(serverApi).health(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        List<String> events = drain(browser);
        assertEquals(List.of("state"), names(events));
        assertTrue(events.get(0).contains("\"running\":false"));
        assertEquals(false, dashboard.state().running());

        advance(Duration.ofSeconds(8));
        dashboard.tick();
        assertTrue(drain(browser).isEmpty());
    }

    @Test
    void aFailedPollMakesTheLinkDownAndASuccessfulOneRecoversIt() throws Exception {
        state.setActiveServer(A);
        create();
        RecordingEmitter browser = subscribe();
        browser.next();

        doThrow(new ResourceAccessException("refused")).when(serverApi).health(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        assertEquals(LinkState.DOWN, dashboard.state().link());
        assertEquals(null, dashboard.state().running());

        doReturn(BUSY).when(serverApi).health(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();

        assertEquals(LinkState.DEGRADED, dashboard.state().link());
        assertEquals(true, dashboard.state().running());
        assertEquals(List.of("link", "link", "state"), names(drain(browser)));
    }

    @Test
    void doesNotPollEverySevenSecondsOnceTheLinkIsLiveButKeepsAOneMinuteCatchAll() {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        clearInvocations(serverApi);

        advance(Duration.ofSeconds(30));
        dashboard.tick();
        advance(Duration.ofSeconds(20));
        dashboard.tick();
        verify(serverApi, never()).health(anyString());

        advance(Duration.ofSeconds(15));
        dashboard.tick();
        verify(serverApi, times(1)).health(A);
    }

    @Test
    void aLiveLinkIgnoresPollResultsForLinkAndRunningButKeepsTheHealthSummary() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        dashboard.runEvent(A, "state", "{\"running\":false}", false);
        RecordingEmitter browser = subscribe();
        browser.next();

        doReturn(new ServerStatusDto(true, true, 9, Duration.ofHours(1))).when(serverApi).health(A);
        advance(Duration.ofSeconds(61));
        dashboard.tick();

        DashboardState result = dashboard.state();
        assertEquals(LinkState.LIVE, result.link());
        assertEquals(false, result.running());
        assertEquals(9, result.health().backupRetentionCount());
        assertEquals(List.of("config"), names(drain(browser)));
    }

    @Test
    void streamEventsAreForwardedAndKeepRunningInStep() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        RecordingEmitter browser = subscribe();
        browser.next();

        dashboard.runEvent(A, "state", "{\"running\":false,\"at\":\"t\"}", false);
        dashboard.runEvent(A, "run-started", "{\"running\":true,\"startedAt\":\"t\"}", true);
        assertEquals(true, dashboard.state().running());
        dashboard.backupsChanged(A, "{\"at\":\"t2\"}");
        dashboard.runEvent(A, "run-finished", "{\"running\":false,\"status\":\"FAILED\"}", false);
        assertEquals(false, dashboard.state().running());

        List<String> events = drain(browser);
        assertEquals(List.of("state", "run-started", "backups-changed", "run-finished"), names(events));
        assertEquals("event:backups-changed\ndata:{\"at\":\"t2\"}\n\n", events.get(2));
    }

    @Test
    void aConfigChangedEventRefetchesHealthOnceAndPushesTheSummaryOnlyWhenItChanged() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        RecordingEmitter browser = subscribe();
        browser.next();
        clearInvocations(serverApi);

        dashboard.configChanged(A);
        assertTrue(drain(browser).isEmpty());
        verify(serverApi, times(1)).health(A);

        doReturn(new ServerStatusDto(true, false, 7, Duration.ofHours(2))).when(serverApi).health(A);
        dashboard.configChanged(A);

        List<String> events = drain(browser);
        assertEquals(List.of("config"), names(events));
        assertEquals("event:config\ndata:{\"backupInterval\":\"PT2H\",\"backupRetentionCount\":7}\n\n", events.get(0));
        verify(serverApi, times(2)).health(A);
    }

    @Test
    void eventsFromAServerThatIsNoLongerActiveAreIgnored() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        state.setActiveServer(B);
        dashboard.sync();
        RecordingEmitter browser = subscribe();
        browser.next();
        clearInvocations(serverApi);

        dashboard.streamUp(A);
        dashboard.runEvent(A, "run-started", "{\"running\":true}", true);
        dashboard.backupsChanged(A, "{}");
        dashboard.configChanged(A);
        dashboard.streamDown(A);

        assertTrue(drain(browser).isEmpty());
        assertEquals(false, dashboard.state().running());
        verify(serverApi, never()).health(anyString());
    }

    @Test
    void switchingServersPushesASnapshotThatClearsEverythingAboutTheOldOne() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        dashboard.runEvent(A, "state", "{\"running\":true}", true);
        RecordingEmitter browser = subscribe();
        browser.next();
        doThrow(new ResourceAccessException("refused")).when(serverApi).health(B);

        state.setActiveServer(B);
        dashboard.sync();

        List<String> events = drain(browser);
        assertEquals(List.of("snapshot", "link", "versions"), names(events));
        String snapshot = events.get(0);
        assertTrue(snapshot.contains("\"activeServer\":\"" + B + "\""), snapshot);
        assertTrue(snapshot.contains("\"knownServers\":[\"" + A + "\",\"" + B + "\"]"), snapshot);
        assertTrue(snapshot.contains("\"link\":\"CONNECTING\"") && snapshot.contains("\"running\":null") && snapshot.contains("\"health\":null"), snapshot);
        assertTrue(snapshot.contains("\"server\":{\"currentVersion\":null") && snapshot.contains("\"checkState\":\"NOT_CHECKED\""), snapshot);
    }

    @Test
    void aHealthAnswerThatArrivesAfterASwitchIsDiscarded() {
        state.setActiveServer(A);
        when(serverApi.health(A)).thenAnswer(call -> {
            state.setActiveServer(B);
            dashboard.sync();
            return BUSY;
        });
        doThrow(new ResourceAccessException("refused")).when(serverApi).health(B);

        create();

        DashboardState result = dashboard.state();
        assertEquals(B, result.activeServer());
        assertEquals(LinkState.DOWN, result.link());
        assertEquals(null, result.running());
        assertEquals(null, result.health());
        verify(serverApi, times(1)).health(A);
        verify(serverApi, times(1)).health(B);
    }

    @Test
    void clearingTheActiveServerGoesDownWithoutPolling() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        RecordingEmitter browser = subscribe();
        browser.next();
        clearInvocations(serverApi);

        state.setActiveServer("");
        dashboard.sync();
        advance(Duration.ofSeconds(30));
        dashboard.tick();

        List<String> events = drain(browser);
        assertEquals(List.of("snapshot"), names(events));
        assertTrue(events.get(0).contains("\"activeServer\":null") && events.get(0).contains("\"link\":\"DOWN\""));
        verify(serverApi, never()).health(anyString());
    }

    @Test
    void knownServerChangesArePushedOnceAndWithoutASnapshotWhenTheActiveServerStaysTheSame() throws Exception {
        state.setActiveServer(A);
        create();
        RecordingEmitter browser = subscribe();
        browser.next();

        state.updateKnownServers(List.of(A, B));

        List<String> events = drain(browser);
        assertEquals(List.of("servers"), names(events));
        assertEquals("event:servers\ndata:{\"knownServers\":[\"" + A + "\",\"" + B + "\"],\"activeServer\":\"" + A + "\"}\n\n", events.get(0));
    }

    @Test
    void aDiscoveryThatPicksTheFirstActiveServerPushesOneSnapshot() throws Exception {
        when(serverApi.health(anyString())).thenThrow(new ResourceAccessException("refused"));
        create();
        RecordingEmitter browser = subscribe();
        browser.next();

        state.updateKnownServers(List.of(A, B));

        assertEquals(List.of("snapshot", "link", "versions"), names(drain(browser)));
        assertEquals(A, dashboard.state().activeServer());
    }

    @Test
    void theServerVersionIsFetchedOnLinkUpAndAServerSwitchNotOnEveryRead() throws Exception {
        state.setActiveServer(A);
        create();
        verify(serverApi, times(1)).serverVersion(A);

        dashboard.state();
        dashboard.subscribe();
        dashboard.state();
        verify(serverApi, times(1)).serverVersion(A);

        dashboard.streamUp(A);
        verify(serverApi, times(1)).serverVersion(A);

        advance(Duration.ofMinutes(6));
        dashboard.streamDown(A);
        verify(serverApi, times(2)).serverVersion(A);

        doThrow(new ResourceAccessException("refused")).when(serverApi).health(B);
        state.setActiveServer(B);
        dashboard.sync();
        verify(serverApi, never()).serverVersion(B);
        doReturn(IDLE).when(serverApi).health(B);
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        verify(serverApi, times(1)).serverVersion(B);
    }

    @Test
    void theServerVersionIsRefetchedAtMostHourlyAndPushedWhenItChanges() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        RecordingEmitter browser = subscribe();
        browser.next();
        clearInvocations(serverApi);

        advance(Duration.ofMinutes(59));
        dashboard.tick();
        verify(serverApi, never()).serverVersion(anyString());

        doReturn(new VersionStatusDto("0.2.0", "0.2.0", null, false, CheckState.OK, START, null)).when(serverApi).serverVersion(A);
        advance(Duration.ofMinutes(2));
        dashboard.tick();

        verify(serverApi, times(1)).serverVersion(A);
        List<String> events = drain(browser).stream().filter(event -> nameOf(event).equals("versions")).toList();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"server\":{\"currentVersion\":\"0.2.0\""), events.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theClientsOwnUpdateCheckIsPushedAsAVersionsEvent() throws Exception {
        state.setActiveServer(A);
        create();
        ArgumentCaptor<Consumer<VersionStatusDto>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(updateCheck).addStatusListener(listener.capture());
        RecordingEmitter browser = subscribe();
        browser.next();
        VersionStatusDto updated = new VersionStatusDto("0.1.0", "0.3.0", "https://example.test/c", true, CheckState.OK, START, null);
        when(updateCheck.status()).thenReturn(updated);

        listener.getValue().accept(updated);

        List<String> events = drain(browser);
        assertEquals(List.of("versions"), names(events));
        assertTrue(events.get(0).contains("\"latestVersion\":\"0.3.0\"") && events.get(0).contains("\"updateAvailable\":true"), events.get(0));
        assertTrue(events.get(0).contains("\"server\":{"));
    }

    @Test
    void retriesAnUnavailableServerVersionAfterTenMinutes() {
        state.setActiveServer(A);
        doThrow(new ResourceAccessException("refused")).when(serverApi).serverVersion(A);
        create();
        dashboard.streamUp(A);
        clearInvocations(serverApi);

        advance(Duration.ofMinutes(9));
        dashboard.tick();
        verify(serverApi, never()).serverVersion(anyString());

        advance(Duration.ofMinutes(1));
        dashboard.tick();
        verify(serverApi, times(1)).serverVersion(A);
    }

    @Test
    void healthRequestsThatPileUpWhileOneIsRunningAreCoalesced() {
        state.setActiveServer(A);
        create(queued::add);
        assertEquals(1, queued.size());

        dashboard.streamUp(A);
        dashboard.configChanged(A);
        dashboard.configChanged(A);

        assertEquals(2, queued.size());
        queued.forEach(Runnable::run);
        verify(serverApi, times(2)).health(A);
        assertFalse(queued.isEmpty());
    }

    @Test
    void aServerIsConnectingUntilItsFirstAnswerAndOnlyThenDownWithAnUnavailableVersion() throws Exception {
        state.setActiveServer(A);
        doThrow(new ResourceAccessException("refused")).when(serverApi).health(A);
        create(queued::add);
        RecordingEmitter browser = subscribe();
        browser.next();

        DashboardState before = dashboard.state();
        assertEquals(LinkState.CONNECTING, before.link());
        assertEquals(CheckState.NOT_CHECKED, before.versions().server().checkState());

        queued.poll().run();

        DashboardState after = dashboard.state();
        assertEquals(LinkState.DOWN, after.link());
        assertEquals(CheckState.UNAVAILABLE, after.versions().server().checkState());
        assertEquals(List.of("link", "versions"), names(drain(browser)));
    }

    @Test
    void aStreamDropGoesBackToConnectingNotStraightToDown() {
        state.setActiveServer(A);
        create(queued::add);
        queued.poll().run();
        dashboard.streamUp(A);

        dashboard.streamDown(A);

        assertEquals(LinkState.CONNECTING, dashboard.state().link());
        assertEquals(null, dashboard.state().running());
    }

    @Test
    void aServerVersionThatWasNotCheckedYetIsFetchedAgainWithinHalfAMinute() throws Exception {
        state.setActiveServer(A);
        VersionStatusDto notChecked = new VersionStatusDto("0.1.0", null, null, false, CheckState.NOT_CHECKED, null, null);
        doReturn(notChecked).doReturn(notChecked).doReturn(SERVER_VERSION).when(serverApi).serverVersion(A);
        create();
        dashboard.streamUp(A);
        RecordingEmitter browser = subscribe();
        browser.next();
        clearInvocations(serverApi);

        advance(Duration.ofSeconds(20));
        dashboard.tick();
        verify(serverApi, never()).serverVersion(anyString());

        advance(Duration.ofSeconds(12));
        dashboard.tick();

        verify(serverApi, times(1)).serverVersion(A);
        assertEquals(CheckState.OK, dashboard.state().versions().server().checkState());
        List<String> events = drain(browser).stream().filter(event -> nameOf(event).equals("versions")).toList();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"checkState\":\"OK\""), events.get(0));
    }

    @Test
    void aFlappingLinkDoesNotRefetchTheServerVersionWithinFiveMinutes() {
        state.setActiveServer(A);
        create();
        clearInvocations(serverApi);

        dashboard.streamUp(A);
        dashboard.streamDown(A);
        dashboard.streamUp(A);

        verify(serverApi, never()).serverVersion(anyString());
    }

    @Test
    void whileDegradedTheClientChecksTheArchiveListOncePerMinuteAndPushesBackupsChanged() throws Exception {
        state.setActiveServer(A);
        List<BackupDto> one = List.of(new BackupDto("a", "a.zip", 1L, START));
        List<BackupDto> two = List.of(new BackupDto("a", "a.zip", 1L, START), new BackupDto("b", "b.zip", 2L, START));
        doReturn(one).when(serverApi).listBackups(A);
        create();
        RecordingEmitter browser = subscribe();
        browser.next();

        advance(Duration.ofSeconds(8));
        dashboard.tick();
        verify(serverApi, times(1)).listBackups(A);
        assertTrue(names(drain(browser)).stream().noneMatch("backups-changed"::equals));

        advance(Duration.ofSeconds(30));
        dashboard.tick();
        verify(serverApi, times(1)).listBackups(A);

        doReturn(two).when(serverApi).listBackups(A);
        advance(Duration.ofSeconds(31));
        dashboard.tick();
        verify(serverApi, times(2)).listBackups(A);
        List<String> events = drain(browser).stream().filter(event -> nameOf(event).equals("backups-changed")).toList();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"at\":\"2026-11-12T10:01:09Z\""), events.get(0));

        advance(Duration.ofSeconds(61));
        dashboard.tick();
        assertTrue(drain(browser).stream().noneMatch(event -> nameOf(event).equals("backups-changed")));
    }

    @Test
    void aLiveLinkNeverChecksTheArchiveListItself() {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        clearInvocations(serverApi);

        advance(Duration.ofMinutes(5));
        dashboard.tick();

        verify(serverApi, never()).listBackups(anyString());
    }

    @Test
    void aRunThatFinishesWhileDegradedRebaselinesTheArchiveListInsteadOfAnnouncingIt() throws Exception {
        state.setActiveServer(A);
        List<BackupDto> one = List.of(new BackupDto("a", "a.zip", 1L, START));
        List<BackupDto> two = List.of(new BackupDto("a", "a.zip", 1L, START), new BackupDto("b", "b.zip", 2L, START));
        doReturn(one).when(serverApi).listBackups(A);
        doReturn(BUSY).when(serverApi).health(A);
        create();
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        RecordingEmitter browser = subscribe();
        browser.next();

        doReturn(IDLE).when(serverApi).health(A);
        doReturn(two).when(serverApi).listBackups(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        advance(Duration.ofSeconds(61));
        dashboard.tick();
        advance(Duration.ofSeconds(61));
        dashboard.tick();

        List<String> names = names(drain(browser));
        assertTrue(names.contains("state"));
        assertFalse(names.contains("backups-changed"));
    }

    @Test
    void aServerThatCameBackAfterBeingDownIsAskedForItsVersionAgainAtOnce() {
        state.setActiveServer(A);
        create();
        verify(serverApi, times(1)).serverVersion(A);

        doThrow(new ResourceAccessException("refused")).when(serverApi).health(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        assertEquals(LinkState.DOWN, dashboard.state().link());
        clearInvocations(serverApi);

        doReturn(IDLE).when(serverApi).health(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();

        assertEquals(LinkState.DEGRADED, dashboard.state().link());
        verify(serverApi, times(1)).serverVersion(A);
    }

    @Test
    void aFailingArchiveListingIsRetriedOnlyOncePerMinute() {
        state.setActiveServer(A);
        doThrow(new ResourceAccessException("timed out")).when(serverApi).listBackups(A);
        create();

        advance(Duration.ofSeconds(8));
        dashboard.tick();
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        advance(Duration.ofSeconds(8));
        dashboard.tick();
        verify(serverApi, times(1)).listBackups(A);

        advance(Duration.ofSeconds(60));
        dashboard.tick();
        verify(serverApi, times(2)).listBackups(A);
    }
}
