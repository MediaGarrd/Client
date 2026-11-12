package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import wellatleastitried.mediagarrdclient.Configuration;
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
        Configuration configuration = new Configuration();
        configuration.setDownloadDirectory(downloads.toString());
        when(state.requireActiveServer()).thenReturn(SERVER);
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
        when(api.downloadById(eq(SERVER), eq("b2"), any(), any())).thenThrow(new IllegalStateException("disk full"));

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

        assertStatus(HttpStatus.NOT_FOUND, () -> pickup.pickupProgress(taskId));
    }

    @Test
    void rejectsUnknownBackupsAndTasks() {
        assertStatus(HttpStatus.NOT_FOUND, () -> pickup.startPickupByIdTask("missing"));
        assertStatus(HttpStatus.NOT_FOUND, () -> pickup.pickupProgress("missing"));
        when(api.listBackups(SERVER)).thenReturn(List.of());
        assertStatus(HttpStatus.NOT_FOUND, () -> pickup.startPickupLatestTask());
    }

    @Test
    void refusesFileNamesThatLeaveTheDownloadDirectory() {
        when(api.listBackups(SERVER)).thenReturn(List.of(new BackupDto("evil", "../evil.zip", 4, Instant.EPOCH)));

        assertThrows(IllegalArgumentException.class, () -> pickup.startPickupLatestTask());
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
        pickup.scheduledPickup();

        assertEquals(List.of("b2-media-backup-AD.zip", "b2-media-backup.zip"), fileNames());
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

    private static void assertStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-26T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
