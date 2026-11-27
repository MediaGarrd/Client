package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.PickupProgressDto;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;

class BackupPickupServiceTest {

    private static final String SERVER = "http://server:8080";
    private static final BackupDto LATEST = new BackupDto("b2", "b2-media-backup.zip", 4, Instant.EPOCH);
    private static final BackupDto OLDER = new BackupDto("b1", "b1-media-backup.zip", 4, Instant.EPOCH);

    @TempDir
    Path downloads;

    private final ClientStateService state = mock(ClientStateService.class);
    private final ServerApiService api = mock(ServerApiService.class);
    private final MutableClock clock = new MutableClock();
    private BackupPickupService pickup;

    @BeforeEach
    void setUp() {
        MediaGarrdProperties configuration = new MediaGarrdProperties();
        configuration.setDownloadDirectory(downloads.toString());
        when(state.requireActiveServer()).thenReturn(SERVER);
        when(state.getActiveServer()).thenReturn(SERVER);
        when(state.hasPairing(SERVER)).thenReturn(true);
        when(state.getPickupInterval()).thenReturn(Duration.ofHours(1));
        when(api.listBackups(SERVER)).thenReturn(List.of(LATEST, OLDER));
        pickup = new BackupPickupService(configuration, state, api, clock);
    }

    @AfterEach
    void tearDown() {
        pickup.shutdown();
    }

    @Test
    void downloadsTheLatestBackup() throws Exception {
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));

        PickupStartDto started = pickup.startPickupLatestTask();
        PickupProgressDto progress = awaitFinished(started.taskId());

        assertEquals("COMPLETED", progress.status());
        assertEquals(downloads.resolve("b2-media-backup.zip").toString(), progress.savedPath());
    }

    @Test
    void reusesTheRunningTaskForTheSameBackup() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return invocation.getArgument(2);
        });

        PickupStartDto first = pickup.startPickupLatestTask();
        PickupStartDto second = pickup.startPickupByIdTask("b2");
        release.countDown();
        awaitFinished(first.taskId());

        assertEquals(first.taskId(), second.taskId());
        verify(api, times(1)).downloadById(anyString(), anyString(), any(), any());
    }

    @Test
    void reusesAnExistingDownload() throws Exception {
        Files.write(downloads.resolve("b1-media-backup.zip"), new byte[4]);

        PickupProgressDto progress = pickup.pickupProgress(pickup.startPickupByIdTask("b1").taskId());

        assertEquals("COMPLETED", progress.status());
        verify(api, never()).downloadById(anyString(), anyString(), any(), any());
    }

    @Test
    void reportsDownloadFailures() throws Exception {
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenThrow(new DownloadFailedException("disk full"));

        PickupProgressDto progress = awaitFinished(pickup.startPickupLatestTask().taskId());

        assertEquals("FAILED", progress.status());
        assertEquals("disk full", progress.error());
    }

    @Test
    void forgetsFinishedTasksAfterTheRetentionPeriod() throws Exception {
        Files.write(downloads.resolve("b1-media-backup.zip"), new byte[4]);
        String taskId = pickup.startPickupByIdTask("b1").taskId();

        clock.advance(BackupPickupService.FINISHED_TASK_RETENTION.plusSeconds(1));
        pickup.startPickupByIdTask("b1");

        assertThrows(NotFoundException.class, () -> pickup.pickupProgress(taskId));
    }

    @Test
    void rejectsUnknownBackupsAndTasks() {
        assertThrows(NotFoundException.class, () -> pickup.startPickupByIdTask("missing"));
        assertThrows(NotFoundException.class, () -> pickup.pickupProgress("missing"));
        when(api.listBackups(SERVER)).thenReturn(List.of());
        assertThrows(NotFoundException.class, () -> pickup.startPickupLatestTask());
    }

    @Test
    void refusesFileNamesThatLeaveTheDownloadDirectory() {
        when(api.listBackups(SERVER)).thenReturn(List.of(new BackupDto("evil", "../evil.zip", 4, Instant.EPOCH)));

        assertThrows(InvalidRequestException.class, () -> pickup.startPickupLatestTask());
    }

    @Test
    void aRefusedFileNameLeavesNoStuckTaskBehind() {
        when(api.listBackups(SERVER)).thenReturn(List.of(new BackupDto("evil", "../evil.zip", 4, Instant.EPOCH)));

        assertThrows(InvalidRequestException.class, () -> pickup.startPickupByIdTask("evil"));
        assertThrows(InvalidRequestException.class, () -> pickup.startPickupByIdTask("evil"));

        verify(api, never()).downloadById(anyString(), anyString(), any(), any());
    }

    @Test
    void aFailedDownloadReportsAGenericMessageInsteadOfItsDetails() throws Exception {
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenThrow(new IllegalStateException("cannot write /var/lib/mediagarrd/downloads/b2.part"));

        PickupProgressDto progress = awaitFinished(pickup.startPickupLatestTask().taskId());

        assertEquals("FAILED", progress.status());
        assertEquals(BackupPickupService.GENERIC_FAILURE, progress.error());
    }

    @Test
    void scheduledPickupDoesNotBlockTheSchedulerThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean downloadFinished = new AtomicBoolean();
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            downloadFinished.set(true);
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });
        clock.advance(Duration.ofHours(2));

        pickup.scheduledPickup();
        boolean returnedWhileDownloading = !downloadFinished.get();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        pickup.scheduledPickup();
        clock.advance(Duration.ofHours(2));
        pickup.scheduledPickup();
        release.countDown();

        assertTrue(returnedWhileDownloading, "the scheduler thread waited for the download");
        verify(api, timeout(5000).times(1)).downloadById(eq(SERVER), eq("b2"), any(), any());
    }

    @Test
    void scheduledPickupNeverDeletesAUsersCopyOfTheLatestArchive() throws IOException {
        Files.write(downloads.resolve("b2-media-backup.zip"), new byte[4]);

        clock.advance(Duration.ofHours(2));
        pickup.runScheduledPickup();

        assertEquals(List.of("b2-media-backup.zip"), fileNames());
        verify(api, never()).downloadById(anyString(), anyString(), any(), any());
    }

    @Test
    void scheduledPickupKeepsOnlyTheLatestAutomaticDownload() throws IOException {
        Files.write(downloads.resolve("b1-media-backup-AD.zip"), new byte[4]);
        Files.write(downloads.resolve("b2-media-backup.zip"), new byte[3]);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });

        clock.advance(Duration.ofHours(2));
        pickup.runScheduledPickup();

        assertEquals(List.of("b2-media-backup-AD.zip", "b2-media-backup.zip"), fileNames());
    }

    @Test
    void scheduledPickupRemovesOnlyOlderListedAutomaticDownloads() throws IOException {
        BackupDto older = new BackupDto("b0", "b0-media-backup.zip", 4, Instant.EPOCH);
        BackupDto newer = new BackupDto("b3", "b3-media-backup.zip", 4, Instant.EPOCH);
        when(api.listBackups(SERVER)).thenReturn(List.of(LATEST, OLDER, older));
        Files.write(downloads.resolve("b1-media-backup-AD.zip"), new byte[4]);
        Files.write(downloads.resolve("b0-media-backup-AD.zip"), new byte[4]);
        Files.write(downloads.resolve("b3-media-backup-AD.zip"), new byte[4]);
        Files.write(downloads.resolve("other-server-media-backup-AD.zip"), new byte[4]);
        Files.write(downloads.resolve("notes-AD.zip"), new byte[4]);
        Files.write(downloads.resolve("b1-media-backup.zip"), new byte[4]);
        Files.createDirectory(downloads.resolve("b0-media-backup-AD.zip.dir"));
        Path outside = Files.write(downloads.resolve("outside.bin"), new byte[4]);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.write(target, new byte[4]);
            return target;
        });

        clock.advance(Duration.ofHours(2));
        pickup.runScheduledPickup();

        assertEquals(List.of("b0-media-backup-AD.zip.dir", "b1-media-backup.zip", "b2-media-backup-AD.zip", "b3-media-backup-AD.zip",
            "notes-AD.zip", "other-server-media-backup-AD.zip", "outside.bin"), fileNames());
        assertTrue(Files.exists(outside));
    }

    @Test
    void scheduledPickupDoesNotFollowSymlinksWhenRemovingSupersededDownloads() throws IOException {
        Path target = Files.write(downloads.resolve("target.bin"), new byte[4]);
        Files.createSymbolicLink(downloads.resolve("b1-media-backup-AD.zip"), target);
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenAnswer(invocation -> {
            Path destination = invocation.getArgument(2);
            Files.write(destination, new byte[4]);
            return destination;
        });

        clock.advance(Duration.ofHours(2));
        pickup.runScheduledPickup();

        assertTrue(Files.isSymbolicLink(downloads.resolve("b1-media-backup-AD.zip")));
        assertTrue(Files.exists(target));
    }

    @Test
    void refusesArchiveNamesThatAreNotZipFilesBeforeDownloading() {
        for (String name : List.of("evil.part", "evil.tar", "evil.zip.part", "evil")) {
            when(api.listBackups(SERVER)).thenReturn(List.of(new BackupDto("evil", name, 4, Instant.EPOCH)));

            assertThrows(InvalidRequestException.class, () -> pickup.startPickupLatestTask());
            assertThrows(InvalidRequestException.class, () -> pickup.startPickupByIdTask("evil"));
            pickup.runScheduledPickup();
        }

        verify(api, never()).downloadById(anyString(), anyString(), any(), any());
    }

    private PickupProgressDto awaitFinished(String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            PickupProgressDto progress = pickup.pickupProgress(taskId);
            if (progress.status().equals("COMPLETED") || progress.status().equals("FAILED")) {
                return progress;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Pickup task " + taskId + " did not finish");
    }

    private List<String> fileNames() throws IOException {
        try (var files = Files.list(downloads)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
