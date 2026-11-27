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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.DashboardState;
import wellatleastitried.mediagarrdclient.dto.LinkState;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;

@Timeout(30)
class DashboardServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
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

    private final MutableClock clock = new MutableClock(START);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final ServerApiService serverApi = mock(ServerApiService.class);
    private final UpdateCheckService updateCheck = mock(UpdateCheckService.class);
    private ClientStateService state;
    private RecordingPublisher publisher;
    private DashboardService dashboard;

    @BeforeEach
    void setUp() {
        MediaGarrdProperties configuration = new MediaGarrdProperties();
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(Duration.ofHours(12));
        state = new ClientStateService(configuration, MAPPER);
        state.storePairing(A, new StoredPairing("token-a", "id-a", "Test"));
        state.storePairing(B, new StoredPairing("token-b", "id-b", "Test"));
        when(updateCheck.status()).thenReturn(CLIENT_VERSION);
        when(serverApi.health(anyString())).thenReturn(IDLE);
        when(serverApi.serverVersion(anyString())).thenReturn(SERVER_VERSION);
    }

    private void create(Executor executor) {
        publisher = new RecordingPublisher();
        dashboard = new DashboardService(state, publisher, serverApi, new VersionService(updateCheck, serverApi, clock), updateCheck, MAPPER, executor, clock);
        dashboard.start();
    }

    private void create() {
        create(Runnable::run);
    }

    private RecordingPublisher subscribe() {
        return dashboard.subscribe(publisher::subscribe).orElseThrow();
    }

    private static List<String> drain(RecordingPublisher emitter) throws InterruptedException {
        List<String> events = new ArrayList<>();
        String next;
        while ((next = emitter.sent.poll(150, TimeUnit.MILLISECONDS)) != null) {
            events.add(next);
        }
        return events;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
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
        clock.advance(duration);
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

        RecordingPublisher browser = subscribe();

        String snapshot = browser.next();
        assertEquals("snapshot", nameOf(snapshot));
        JsonNode data = RecordingPublisher.data(snapshot);
        assertEquals(A, data.path("activeServer").asString());
        assertEquals(List.of(A), strings(data.path("knownServers")));
        assertEquals("DEGRADED", data.path("link").asString());
        assertTrue(data.path("running").isBoolean() && !data.path("running").asBoolean());
        assertEquals("PT6H", data.path("health").path("backupInterval").asString());
        assertEquals(5, data.path("health").path("backupRetentionCount").asInt());
        assertTrue(data.path("versions").path("server").isObject());
        assertEquals("0.2.0", data.path("versions").path("server").path("latestVersion").asString());
        assertTrue(drain(browser).isEmpty());
    }

    @Test
    void aLateSubscriberGetsTheLatestSnapshotFollowedByGranularEvents() throws Exception {
        state.setActiveServer(A);
        create();
        dashboard.streamUp(A);
        dashboard.runEvent(A, "run-started", "{\"running\":true,\"startedAt\":\"t\"}", true);

        RecordingPublisher browser = subscribe();
        dashboard.runEvent(A, "run-finished", "{\"running\":false,\"status\":\"COMPLETED\"}", false);

        List<String> events = drain(browser);
        assertEquals(List.of("snapshot", "run-finished"), names(events));
        JsonNode snapshot = RecordingPublisher.data(events.get(0));
        assertEquals("LIVE", snapshot.path("link").asString());
        assertTrue(snapshot.path("running").asBoolean());
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
        RecordingPublisher browser = subscribe();
        browser.next();

        dashboard.streamUp(A);
        dashboard.streamUp(A);
        dashboard.streamDown(A);

        List<String> links = drain(browser).stream().filter(event -> nameOf(event).equals("link")).toList();
        assertEquals(3, links.size());
        assertEquals("LIVE", RecordingPublisher.data(links.get(0)).path("state").asString());
        assertEquals(A, RecordingPublisher.data(links.get(0)).path("server").asString());
        assertEquals("CONNECTING", RecordingPublisher.data(links.get(1)).path("state").asString());
        assertEquals("DEGRADED", RecordingPublisher.data(links.get(2)).path("state").asString());
    }

    @Test
    void pollsHealthEverySevenToEightSecondsWhileNotLiveAndFeedsTheState() throws Exception {
        state.setActiveServer(A);
        create();
        RecordingPublisher browser = subscribe();
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
        assertFalse(RecordingPublisher.data(events.get(0)).path("running").asBoolean(true));
        assertEquals(false, dashboard.state().running());

        advance(Duration.ofSeconds(8));
        dashboard.tick();
        assertTrue(drain(browser).isEmpty());
    }

    @Test
    void aFailedPollMakesTheLinkDownAndASuccessfulOneRecoversIt() throws Exception {
        state.setActiveServer(A);
        create();
        RecordingPublisher browser = subscribe();
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
        RecordingPublisher browser = subscribe();
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
        RecordingPublisher browser = subscribe();
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
        RecordingPublisher browser = subscribe();
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
        RecordingPublisher browser = subscribe();
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
        RecordingPublisher browser = subscribe();
        browser.next();
        doThrow(new ResourceAccessException("refused")).when(serverApi).health(B);

        state.setActiveServer(B);
        dashboard.sync();

        List<String> events = drain(browser);
        assertEquals(List.of("snapshot", "link", "versions"), names(events));
        String snapshot = events.get(0);
        JsonNode data = RecordingPublisher.data(snapshot);
        assertEquals(B, data.path("activeServer").asString());
        assertEquals(List.of(A, B), strings(data.path("knownServers")));
        assertEquals("CONNECTING", data.path("link").asString());
        assertTrue(data.path("running").isNull());
        assertTrue(data.path("health").isNull());
        assertTrue(data.path("versions").path("server").path("currentVersion").isNull());
        assertEquals("NOT_CHECKED", data.path("versions").path("server").path("checkState").asString());
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
        RecordingPublisher browser = subscribe();
        browser.next();
        clearInvocations(serverApi);

        state.setActiveServer("");
        dashboard.sync();
        advance(Duration.ofSeconds(30));
        dashboard.tick();

        List<String> events = drain(browser);
        assertEquals(List.of("snapshot"), names(events));
        assertTrue(RecordingPublisher.data(events.get(0)).path("activeServer").isNull());
        assertEquals("DOWN", RecordingPublisher.data(events.get(0)).path("link").asString());
        verify(serverApi, never()).health(anyString());
    }

    @Test
    void knownServerChangesArePushedOnceAndWithoutASnapshotWhenTheActiveServerStaysTheSame() throws Exception {
        state.setActiveServer(A);
        create();
        RecordingPublisher browser = subscribe();
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
        RecordingPublisher browser = subscribe();
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
        dashboard.subscribe(publisher::subscribe);
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
        RecordingPublisher browser = subscribe();
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
        assertEquals("0.2.0", RecordingPublisher.data(events.get(0)).path("server").path("currentVersion").asString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theClientsOwnUpdateCheckIsPushedAsAVersionsEvent() throws Exception {
        state.setActiveServer(A);
        create();
        ArgumentCaptor<Consumer<VersionStatusDto>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(updateCheck).addStatusListener(listener.capture());
        RecordingPublisher browser = subscribe();
        browser.next();
        VersionStatusDto updated = new VersionStatusDto("0.1.0", "0.3.0", "https://example.test/c", true, CheckState.OK, START, null);
        when(updateCheck.status()).thenReturn(updated);

        listener.getValue().accept(updated);

        List<String> events = drain(browser);
        assertEquals(List.of("versions"), names(events));
        JsonNode versions = RecordingPublisher.data(events.get(0));
        assertEquals("0.3.0", versions.path("client").path("latestVersion").asString());
        assertTrue(versions.path("client").path("updateAvailable").asBoolean());
        assertTrue(versions.path("server").isObject());
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
        RecordingPublisher browser = subscribe();
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
        RecordingPublisher browser = subscribe();
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
        assertEquals("OK", RecordingPublisher.data(events.get(0)).path("server").path("checkState").asString());
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
        RecordingPublisher browser = subscribe();
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
        assertEquals("2026-11-12T10:01:09Z", RecordingPublisher.data(events.get(0)).path("at").asString());

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
        RecordingPublisher browser = subscribe();
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

    @Test
    void aServerWithoutAStoredTokenRequiresPairingAndShowsNoDetails() {
        state.forgetPairing(A);
        create();
        state.setActiveServer(A);

        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.REQUIRED, dashboard.state().pairing());
        assertEquals(null, dashboard.state().health());
    }

    @Test
    void aRejectedTokenOnTheHealthCheckMarksPairingRejectedAndKeepsTheServerReachable() {
        create();
        doThrow(new PairingRequiredException("rejected")).when(serverApi).health(A);
        state.setActiveServer(A);

        DashboardState result = dashboard.state();

        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.REJECTED, result.pairing());
        assertEquals(wellatleastitried.mediagarrdclient.dto.LinkState.DEGRADED, result.link());
    }

    @Test
    void aDeferredPairingWhoseHealthCheckLaterGetsA401BecomesRejected() {
        create();
        state.forgetPairing(A);
        state.setActiveServer(A);
        doThrow(new PairingRequiredException("rejected")).when(serverApi).health(A);
        state.storePairing(A, new StoredPairing("deferred-token", "id-d", "Test"));

        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.REJECTED, dashboard.state().pairing());
    }

    @Test
    void pairingAgainClearsTheRejection() {
        create();
        doThrow(new PairingRequiredException("rejected")).when(serverApi).health(A);
        state.setActiveServer(A);
        doReturn(IDLE).when(serverApi).health(A);

        state.storePairing(A, new StoredPairing("new-token", "id-new", "Test"));

        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.PAIRED, dashboard.state().pairing());
    }

    @Test
    void aHealthCheckThatClearsTheRejectionTellsListenersSoTheStreamCanRestart() {
        create();
        doThrow(new PairingRequiredException("rejected")).when(serverApi).health(A);
        state.setActiveServer(A);
        List<String> recovered = new ArrayList<>();
        dashboard.addRecoveryListener(recovered::add);
        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.REJECTED, dashboard.state().pairing());

        doReturn(IDLE).when(serverApi).health(A);
        advance(Duration.ofSeconds(8));
        dashboard.tick();

        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.PAIRED, dashboard.state().pairing());
        assertEquals(List.of(A), recovered);
    }

    @Test
    void aTokenRejectionSeenByAnyServerCallMarksThePairingRejectedAtOnce() {
        create();
        state.setActiveServer(A);
        org.mockito.ArgumentCaptor<java.util.function.Consumer<String>> listener = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(serverApi).addTokenRejectedListener(listener.capture());
        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.PAIRED, dashboard.state().pairing());

        listener.getValue().accept(A);

        assertEquals(wellatleastitried.mediagarrdclient.dto.PairingState.REJECTED, dashboard.state().pairing());
    }
}
