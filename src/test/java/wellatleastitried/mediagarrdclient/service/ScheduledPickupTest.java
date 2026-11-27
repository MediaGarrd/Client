package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.PickupProgressDto;

class ScheduledPickupTest {

    private static final String SERVER = "http://server:8080";
    private static final Duration INTERVAL = Duration.ofHours(12);
    private static final BackupDto LATEST = new BackupDto("b2", "b2-media-backup.zip", 4, Instant.EPOCH);
    private static final BackupDto OLDER = new BackupDto("b1", "b1-media-backup.zip", 4, Instant.EPOCH);

    @TempDir
    Path tempDir;

    private final ServerApiService api = mock(ServerApiService.class);
    private final MutableClock clock = new MutableClock();
    private final List<BackupPickupService> services = new ArrayList<>();
    private MediaGarrdProperties configuration;
    private ClientStateService state;
    private Path downloads;

    @BeforeEach
    void setUp() throws IOException {
        downloads = tempDir.resolve("downloads");
        Files.createDirectories(downloads);
        configuration = new MediaGarrdProperties();
        configuration.setDownloadDirectory(downloads.toString());
        configuration.setStateFile(tempDir.resolve("state.json").toString());
        configuration.setPickupInterval(INTERVAL);
        state = new ClientStateService(configuration, new JsonMapper());
        when(api.listBackups(SERVER)).thenReturn(List.of(LATEST, OLDER));
        when(api.downloadById(eq(SERVER), anyString(), any(), any())).thenAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
    }

    @AfterEach
    void tearDown() {
        services.forEach(BackupPickupService::shutdown);
    }

    @Test
    void aRestartedClientThatIsOverdueDownloadsOnTheFirstTick() throws Exception {
        pairedState().recordPickup(SERVER, clock.instant().minus(INTERVAL).minusSeconds(1));

        BackupPickupService restarted = restart();
        tick(restarted);

        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
        assertEquals(clock.instant(), pairedState().getLastPickupAt());
    }

    @Test
    void aRestartedClientThatIsNotDueWaitsForTheOriginalDueTime() throws Exception {
        Instant last = clock.instant().minus(Duration.ofHours(5));
        pairedState().recordPickup(SERVER, last);

        BackupPickupService restarted = restart();
        tick(restarted);
        verify(api, never()).downloadById(anyString(), anyString(), any(), any());

        clock.advance(Duration.ofHours(7).minusSeconds(1));
        tick(restarted);
        verify(api, never()).downloadById(anyString(), anyString(), any(), any());

        clock.advance(Duration.ofSeconds(1));
        tick(restarted);
        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
        assertEquals(clock.instant(), pairedState().getLastPickupAt());
    }

    @Test
    void aClientThatNeverPickedUpDownloadsAtOnce() throws Exception {
        pairedState();

        tick(start());

        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
        assertEquals(clock.instant(), pairedState().getLastPickupAt());
    }

    @Test
    void anAlreadyDownloadedLatestArchiveCountsAsAPickup() throws Exception {
        pairedState();
        Files.write(downloads.resolve("b2-media-backup.zip"), new byte[4]);

        tick(start());

        verify(api, never()).downloadById(anyString(), anyString(), any(), any());
        assertEquals(clock.instant(), pairedState().getLastPickupAt());
    }

    @Test
    void anUnpairedClientStaysQuietAndPicksUpOnceItIsPaired() throws Exception {
        state.setActiveServer(SERVER);
        BackupPickupService pickup = start();

        tick(pickup);
        clock.advance(Duration.ofHours(1));
        tick(pickup);

        verify(api, never()).listBackups(anyString());
        assertNull(state.getLastPickupAt());

        state.storePairing(SERVER, new StoredPairing("token", "id", "name"));
        tick(pickup);

        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
        assertEquals(clock.instant(), state.getLastPickupAt());
    }

    @Test
    void aServerThatIsDownIsRetriedOnABackoffUntilItAnswers() throws Exception {
        pairedState();
        doThrow(new ResourceAccessException("connection refused")).when(api).listBackups(SERVER);
        BackupPickupService pickup = start();

        tick(pickup);
        tick(pickup);
        verify(api, times(1)).listBackups(SERVER);

        clock.advance(Duration.ofMinutes(1));
        tick(pickup);
        verify(api, times(2)).listBackups(SERVER);

        clock.advance(Duration.ofMinutes(1));
        tick(pickup);
        verify(api, times(2)).listBackups(SERVER);

        doReturn(List.of(LATEST, OLDER)).when(api).listBackups(SERVER);
        clock.advance(Duration.ofMinutes(1));
        tick(pickup);

        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
        assertEquals(clock.instant(), pairedState().getLastPickupAt());
    }

    @Test
    void aServerWithoutBackupsIsReportedOnceAtInfoAndNeverAsAWarning() throws Exception {
        pairedState();
        doReturn(List.of()).when(api).listBackups(SERVER);
        BackupPickupService pickup = start();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BackupPickupService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (int i = 0; i < 4; i++) {
                tick(pickup);
                clock.advance(Duration.ofMinutes(20));
            }
            verify(api, times(4)).listBackups(SERVER);
        } finally {
            logger.detachAppender(logs);
        }

        assertTrue(logs.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)));
        assertEquals(1, logs.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO && event.getFormattedMessage().contains("no backups")).count());
    }

    @Test
    void switchingTheActiveServerReportsAnEmptyServerAgain() throws Exception {
        pairedState();
        doReturn(List.of()).when(api).listBackups(SERVER);
        BackupPickupService pickup = start();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BackupPickupService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            tick(pickup);
            clock.advance(Duration.ofMinutes(20));
            state.setActiveServer("http://other:8080");
            state.setActiveServer(SERVER);
            tick(pickup);
        } finally {
            logger.detachAppender(logs);
        }

        assertEquals(2, logs.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO && event.getFormattedMessage().contains("no backups")).count());
    }

    @Test
    void aRejectedTokenIsRetriedOnABackoffAndKeepsTheLastPickup() throws Exception {
        Instant last = clock.instant().minus(INTERVAL).minusSeconds(1);
        pairedState().recordPickup(SERVER, last);
        doThrow(new PairingRequiredException("rejected")).when(api).listBackups(SERVER);
        BackupPickupService pickup = start();

        tick(pickup);
        tick(pickup);

        verify(api, times(1)).listBackups(SERVER);
        assertEquals(last, pairedState().getLastPickupAt());
    }

    @Test
    void aDownloadInterruptedByARestartIsRepeatedAfterwards() throws Exception {
        pairedState();
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenThrow(new DownloadFailedException("connection reset"));

        tick(restart());

        assertNull(pairedState().getLastPickupAt());

        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
        tick(restart());

        verify(api, times(2)).downloadById(eq(SERVER), eq("b2"), any(), any());
        assertEquals(clock.instant(), pairedState().getLastPickupAt());
    }

    @Test
    void shorteningTheIntervalMakesAnOverduePickupDue() throws Exception {
        pairedState().recordPickup(SERVER, clock.instant().minus(Duration.ofHours(5)));
        BackupPickupService pickup = start();
        tick(pickup);
        verify(api, never()).downloadById(anyString(), anyString(), any(), any());

        pickup.updatePickupInterval(Duration.ofHours(4));
        tick(pickup);

        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
    }

    @Test
    void lengtheningTheIntervalMovesTheDueTimeFromTheLastPickup() throws Exception {
        Instant last = clock.instant().minus(Duration.ofHours(5));
        pairedState().recordPickup(SERVER, last);
        BackupPickupService pickup = start();

        pickup.updatePickupInterval(Duration.ofHours(8));
        assertEquals(last.plus(Duration.ofHours(8)), pickup.nextPickup());
        clock.advance(Duration.ofHours(3).minusSeconds(1));
        tick(pickup);
        verify(api, never()).downloadById(anyString(), anyString(), any(), any());

        clock.advance(Duration.ofSeconds(1));
        tick(pickup);
        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
    }

    @Test
    void aManualDownloadOfTheLatestArchiveRecordsAPickup() throws Exception {
        pairedState();
        BackupPickupService pickup = start();

        PickupProgressDto progress = awaitFinished(pickup, pickup.startPickupLatestTask().taskId());

        assertEquals("COMPLETED", progress.status());
        assertEquals(clock.instant(), state.getLastPickupAt());
    }

    @Test
    void aManualDownloadOfAnOlderArchiveDoesNotRecordAPickup() throws Exception {
        pairedState();
        BackupPickupService pickup = start();

        PickupProgressDto progress = awaitFinished(pickup, pickup.startPickupByIdTask("b1").taskId());

        assertEquals("COMPLETED", progress.status());
        assertNull(state.getLastPickupAt());
    }

    @Test
    void aFailedManualDownloadDoesNotRecordAPickup() throws Exception {
        pairedState();
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenThrow(new DownloadFailedException("disk full"));
        BackupPickupService pickup = start();

        PickupProgressDto progress = awaitFinished(pickup, pickup.startPickupLatestTask().taskId());

        assertEquals("FAILED", progress.status());
        assertNull(state.getLastPickupAt());
    }

    @Test
    void aScheduledPickupAndAManualOneNeverDownloadTheSameBackupTwice() throws Exception {
        pairedState();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
        BackupPickupService pickup = start();

        pickup.scheduledPickup();
        started.await(5, TimeUnit.SECONDS);
        String taskId = pickup.startPickupLatestTask().taskId();
        release.countDown();
        awaitIdle(pickup);
        awaitFinished(pickup, taskId);

        verify(api, times(1)).downloadById(anyString(), anyString(), any(), any());
    }

    @Test
    void switchingTheActiveServerMakesTheNextPickupDueAtOnce() throws Exception {
        pairedState().recordPickup(SERVER, clock.instant());

        state.setActiveServer(SERVER);
        assertEquals(clock.instant(), state.getLastPickupAt());

        state.setActiveServer("http://other:8080");

        assertNull(state.getLastPickupAt());
        assertNull(new ClientStateService(configuration, new JsonMapper()).getLastPickupAt());
    }

    @Test
    void aFailedRecordOfThePickupBacksOffInsteadOfRetryingEveryTick() throws Exception {
        ClientStateService failing = mock(ClientStateService.class);
        when(failing.getActiveServer()).thenReturn(SERVER);
        when(failing.requireActiveServer()).thenReturn(SERVER);
        when(failing.hasPairing(SERVER)).thenReturn(true);
        when(failing.getPickupInterval()).thenReturn(INTERVAL);
        doThrow(new IllegalStateException("disk full")).when(failing).recordPickup(any(), any());
        BackupPickupService pickup = new BackupPickupService(configuration, failing, api, clock);
        services.add(pickup);

        tick(pickup);
        tick(pickup);
        verify(api, times(1)).listBackups(SERVER);

        clock.advance(Duration.ofMinutes(1));
        tick(pickup);
        verify(api, times(2)).listBackups(SERVER);
    }

    @Test
    void aPickupThatFinishesAfterTheServerWasSwitchedDoesNotDelayTheNewServer() throws Exception {
        pairedState();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
        BackupPickupService pickup = start();

        pickup.scheduledPickup();
        started.await(5, TimeUnit.SECONDS);
        state.setActiveServer("http://other:8080");
        release.countDown();
        awaitIdle(pickup);

        assertNull(state.getLastPickupAt());
        assertNull(new ClientStateService(configuration, new JsonMapper()).getLastPickupAt());
    }

    @Test
    void aManualLatestDownloadThatFinishesAfterTheServerWasSwitchedDoesNotDelayTheNewServer() throws Exception {
        pairedState();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
        BackupPickupService pickup = start();

        String taskId = pickup.startPickupLatestTask().taskId();
        started.await(5, TimeUnit.SECONDS);
        state.setActiveServer("http://other:8080");
        release.countDown();

        assertEquals("COMPLETED", awaitFinished(pickup, taskId).status());
        assertNull(state.getLastPickupAt());
    }

    @Test
    void aScheduledPickupIsRecordedBeforeItsTaskReportsCompleted() throws Exception {
        pairedState();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
        ClientStateService spied = spy(state);
        BackupPickupService pickup = new BackupPickupService(configuration, spied, api, clock);
        services.add(pickup);
        List<String> statusWhenRecorded = new ArrayList<>();
        String[] taskId = new String[1];
        doAnswer(invocation -> {
            statusWhenRecorded.add(pickup.pickupProgress(taskId[0]).status());
            return invocation.callRealMethod();
        }).when(spied).recordPickup(anyString(), any());

        pickup.scheduledPickup();
        started.await(5, TimeUnit.SECONDS);
        taskId[0] = pickup.startPickupLatestTask().taskId();
        release.countDown();
        awaitIdle(pickup);

        assertEquals(List.of("RUNNING"), statusWhenRecorded);
        assertEquals("COMPLETED", pickup.pickupProgress(taskId[0]).status());
    }

    @Test
    void aTickDuringAManualLatestDownloadDefersAndTheManualDownloadRecordsThePickup() throws Exception {
        pairedState();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
        BackupPickupService pickup = start();

        String taskId = pickup.startPickupLatestTask().taskId();
        started.await(5, TimeUnit.SECONDS);
        tick(pickup);

        assertNull(state.getLastPickupAt());
        clock.advance(Duration.ofSeconds(30));
        tick(pickup);
        verify(api, times(1)).downloadById(anyString(), anyString(), any(), any());

        release.countDown();
        assertEquals("COMPLETED", awaitFinished(pickup, taskId).status());

        assertEquals(clock.instant(), state.getLastPickupAt());
        clock.advance(Duration.ofMinutes(5));
        tick(pickup);
        verify(api, times(1)).downloadById(anyString(), anyString(), any(), any());
    }

    @Test
    void aPartFileLeftByACrashIsRemovedByTheNextPickup() throws Exception {
        pairedState();
        Path junk = downloads.resolve("b0-media-backup-AD.zip.part");
        Path unrelated = downloads.resolve("notes.txt");
        Files.write(junk, new byte[7]);
        Files.write(unrelated, new byte[1]);

        tick(start());

        verify(api, times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
        assertEquals(clock.instant(), state.getLastPickupAt());
        assertFalse(Files.exists(junk));
        assertTrue(Files.exists(unrelated));
        assertTrue(Files.exists(downloads.resolve("b2-media-backup-AD.zip")));
    }

    @Test
    void aPartFileIsRemovedBeforeAManualPickupToo() throws Exception {
        pairedState();
        Path junk = downloads.resolve("b9-media-backup.zip.part");
        Files.write(junk, new byte[7]);
        BackupPickupService pickup = start();

        assertEquals("COMPLETED", awaitFinished(pickup, pickup.startPickupByIdTask("b1").taskId()).status());

        assertFalse(Files.exists(junk));
    }

    @Test
    void aPartFileLeftByACrashIsRemovedAtStartup() throws Exception {
        Path junk = downloads.resolve("b2-media-backup-AD.zip.part");
        Files.write(junk, new byte[7]);

        start().removeStalePartFilesAtStartup();

        assertFalse(Files.exists(junk));
    }

    @Test
    void aPartFileOfATransferInProgressIsNeverRemoved() throws Exception {
        pairedState();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.write(target.resolveSibling(target.getFileName() + ".part"), new byte[2]);
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            Files.write(target, new byte[4]);
            return target;
        });
        Path junk = downloads.resolve("b0-media-backup.zip.part");
        Files.write(junk, new byte[7]);
        BackupPickupService pickup = start();

        pickup.scheduledPickup();
        started.await(5, TimeUnit.SECONDS);
        assertEquals("COMPLETED", awaitFinished(pickup, pickup.startPickupByIdTask("b1").taskId()).status());
        Path inProgress = downloads.resolve("b2-media-backup-AD.zip.part");
        boolean keptWhileRunning = Files.exists(inProgress);
        boolean junkGone = !Files.exists(junk);
        release.countDown();
        awaitIdle(pickup);

        assertTrue(keptWhileRunning);
        assertTrue(junkGone);
        assertEquals(clock.instant(), state.getLastPickupAt());
    }

    private ClientStateService pairedState() {
        state.setActiveServer(SERVER);
        state.storePairing(SERVER, new StoredPairing("token", "id", "name"));
        return state;
    }

    private BackupPickupService restart() {
        state = new ClientStateService(configuration, new JsonMapper());
        return start();
    }

    private BackupPickupService start() {
        BackupPickupService service = new BackupPickupService(configuration, state, api, clock);
        services.add(service);
        return service;
    }

    private void tick(BackupPickupService pickup) throws InterruptedException {
        pickup.scheduledPickup();
        awaitIdle(pickup);
    }

    private static void awaitIdle(BackupPickupService pickup) throws InterruptedException {
        for (int attempt = 0; attempt < 500 && pickup.scheduledRunning(); attempt++) {
            Thread.sleep(10);
        }
        if (pickup.scheduledRunning()) {
            throw new AssertionError("The scheduled pickup did not finish");
        }
    }

    private static PickupProgressDto awaitFinished(BackupPickupService pickup, String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            PickupProgressDto progress = pickup.pickupProgress(taskId);
            if (progress.status().equals("COMPLETED") || progress.status().equals("FAILED")) {
                return progress;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Pickup task " + taskId + " did not finish");
    }
}
