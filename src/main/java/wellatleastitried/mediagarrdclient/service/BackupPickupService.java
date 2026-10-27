package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.PickupProgressDto;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;

@Service
public class BackupPickupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupPickupService.class);
    private static final String AUTO_DOWNLOAD_SUFFIX = "-AD";
    private static final String ZIP_SUFFIX = ".zip";
    static final Duration FINISHED_TASK_RETENTION = Duration.ofMinutes(10);

    private enum PickupStatus { QUEUED, RUNNING, COMPLETED, FAILED }

    private final class PickupTask {
        private final String taskId = UUID.randomUUID().toString();
        private final BackupDto backup;
        private volatile PickupStatus status = PickupStatus.QUEUED;
        private volatile long bytesWritten;
        private volatile long totalBytes;
        private volatile Path savedPath;
        private volatile String error;
        private volatile Instant finishedAt;

        private PickupTask(BackupDto backup) {
            this.backup = backup;
            this.totalBytes = backup.sizeBytes();
        }

        private boolean finished() {
            return finishedAt != null;
        }

        private void complete(Path path) {
            savedPath = path;
            bytesWritten = Math.max(bytesWritten, totalBytes);
            totalBytes = Math.max(totalBytes, bytesWritten);
            status = PickupStatus.COMPLETED;
            finishedAt = clock.instant();
        }

        private void fail(String message) {
            error = message;
            status = PickupStatus.FAILED;
            finishedAt = clock.instant();
        }

        private PickupStartDto started() {
            return new PickupStartDto(taskId, backup.id(), backup.fileName(), totalBytes);
        }

        private PickupProgressDto progress() {
            return new PickupProgressDto(taskId, status.name(), backup.id(), backup.fileName(), bytesWritten, totalBytes,
                savedPath == null ? null : savedPath.toString(), error);
        }
    }

    private final Configuration configuration;
    private final ClientStateService clientStateService;
    private final ServerApiService serverApiService;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, PickupTask> tasks = new LinkedHashMap<>();
    private volatile Instant nextPickup;

    @Autowired
    public BackupPickupService(Configuration configuration, ClientStateService clientStateService, ServerApiService serverApiService) {
        this(configuration, clientStateService, serverApiService, Clock.systemUTC());
    }

    BackupPickupService(Configuration configuration, ClientStateService clientStateService, ServerApiService serverApiService, Clock clock) {
        this.configuration = configuration;
        this.clientStateService = clientStateService;
        this.serverApiService = serverApiService;
        this.clock = clock;
        this.nextPickup = clock.instant().plus(clientStateService.getPickupInterval());
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduledPickup() {
        Instant now = clock.instant();
        if (now.isBefore(nextPickup)) {
            return;
        }
        nextPickup = now.plus(clientStateService.getPickupInterval());
        try {
            LOGGER.info("Scheduled pickup saved {}, next pickup at {}", pickupLatestAuto(), nextPickup);
        } catch (RuntimeException e) {
            LOGGER.warn("Scheduled pickup failed, next attempt at {}: {}", nextPickup, e.getMessage());
        }
    }

    public Duration updatePickupInterval(Duration interval) {
        Duration updated = clientStateService.setPickupInterval(interval);
        nextPickup = clock.instant().plus(updated);
        return updated;
    }

    public PickupStartDto startPickupLatestTask() {
        String server = clientStateService.requireActiveServer();
        return startPickupTask(server, latest(serverApiService.listBackups(server)));
    }

    public PickupStartDto startPickupByIdTask(String backupId) {
        String server = clientStateService.requireActiveServer();
        BackupDto backup = serverApiService.listBackups(server).stream()
            .filter(candidate -> candidate.id().equals(backupId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup not found: " + backupId));
        return startPickupTask(server, backup);
    }

    public synchronized PickupProgressDto pickupProgress(String taskId) {
        PickupTask task = tasks.get(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pickup task not found: " + taskId);
        }
        return task.progress();
    }

    private synchronized PickupStartDto startPickupTask(String server, BackupDto backup) {
        evictFinishedTasks();
        PickupTask running = tasks.values().stream()
            .filter(task -> !task.finished() && task.backup.id().equals(backup.id()))
            .findFirst()
            .orElse(null);
        if (running != null) {
            LOGGER.info("Backup {} is already being downloaded by task {}", backup.id(), running.taskId);
            return running.started();
        }

        PickupTask task = new PickupTask(backup);
        tasks.put(task.taskId, task);
        Path downloads = downloadsDirectory();
        Path existing = existingCopy(downloads, backup);
        if (existing != null) {
            LOGGER.info("Backup {} is already downloaded at {}", backup.id(), existing);
            task.complete(existing);
        } else {
            executor.submit(() -> download(server, inside(downloads, backup.fileName()), task));
        }
        return task.started();
    }

    private void download(String server, Path destination, PickupTask task) {
        task.status = PickupStatus.RUNNING;
        try {
            Path saved = serverApiService.downloadById(server, task.backup.id(), destination, (written, total) -> {
                task.bytesWritten = written;
                if (total > 0) {
                    task.totalBytes = total;
                }
            });
            task.complete(saved);
            LOGGER.info("Pickup task {} saved {}", task.taskId, saved);
        } catch (RuntimeException e) {
            task.fail(e.getMessage());
            LOGGER.warn("Pickup task {} failed for backup {}: {}", task.taskId, task.backup.id(), e.getMessage());
        }
    }

    private void evictFinishedTasks() {
        Instant cutoff = clock.instant().minus(FINISHED_TASK_RETENTION);
        tasks.values().removeIf(task -> task.finished() && task.finishedAt.isBefore(cutoff));
    }

    private Path pickupLatestAuto() {
        String server = clientStateService.requireActiveServer();
        List<BackupDto> backups = serverApiService.listBackups(server);
        BackupDto latest = latest(backups);
        Path downloads = downloadsDirectory();
        Path existing = existingCopy(downloads, latest);
        if (existing != null) {
            LOGGER.info("Scheduled pickup skipped, backup {} is already downloaded at {}", latest.id(), existing);
            return existing;
        }

        Path downloaded = serverApiService.downloadById(server, latest.id(), inside(downloads, autoFileName(latest)), null);
        deleteIfSameSize(inside(downloads, latest.fileName()), latest);
        if (backups.size() > 1) {
            deleteQuietly(inside(downloads, autoFileName(backups.get(1))));
        }
        return downloaded;
    }

    private Path existingCopy(Path downloads, BackupDto backup) {
        return Stream.of(autoFileName(backup), backup.fileName())
            .map(name -> inside(downloads, name))
            .filter(path -> hasSize(path, backup.sizeBytes()))
            .findFirst()
            .orElse(null);
    }

    private void deleteIfSameSize(Path path, BackupDto backup) {
        if (hasSize(path, backup.sizeBytes())) {
            deleteQuietly(path);
        }
    }

    private static boolean hasSize(Path path, long size) {
        try {
            return Files.isRegularFile(path) && Files.size(path) == size;
        } catch (IOException e) {
            LOGGER.warn("Could not inspect {}: {}", path, e.getMessage());
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.deleteIfExists(path)) {
                LOGGER.info("Removed superseded download {}", path.getFileName());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not remove {}: {}", path, e.getMessage());
        }
    }

    private static BackupDto latest(List<BackupDto> backups) {
        if (backups.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No backups found on server");
        }
        return backups.getFirst();
    }

    private static String autoFileName(BackupDto backup) {
        String name = backup.fileName();
        return name.endsWith(ZIP_SUFFIX)
            ? name.substring(0, name.length() - ZIP_SUFFIX.length()) + AUTO_DOWNLOAD_SUFFIX + ZIP_SUFFIX
            : name + AUTO_DOWNLOAD_SUFFIX;
    }

    private static Path inside(Path downloads, String fileName) {
        Path path = downloads.resolve(fileName).normalize();
        if (!downloads.equals(path.getParent())) {
            throw new IllegalArgumentException("Backup file name must not leave the download directory: " + fileName);
        }
        return path;
    }

    private Path downloadsDirectory() {
        return Path.of(configuration.getDownloadDirectory()).toAbsolutePath().normalize();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
