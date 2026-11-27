package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.PickupProgressDto;
import wellatleastitried.mediagarrdclient.dto.PickupStartDto;

@Service
public class BackupPickupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupPickupService.class);
    private static final String AUTO_DOWNLOAD_SUFFIX = "-AD";
    private static final String ZIP_SUFFIX = ".zip";
    private static final String PART_SUFFIX = ".part";
    static final Duration FINISHED_TASK_RETENTION = Duration.ofMinutes(10);
    static final String GENERIC_FAILURE = "The download failed, see the client log for details";

    private enum PickupStatus { QUEUED, RUNNING, COMPLETED, FAILED }

    private final class PickupTask {
        private final String taskId = UUID.randomUUID().toString();
        private final BackupDto backup;
        private final boolean latest;
        private volatile PickupStatus status = PickupStatus.QUEUED;
        private volatile long bytesWritten;
        private volatile long totalBytes;
        private volatile Path savedPath;
        private volatile String error;
        private volatile Instant finishedAt;

        private PickupTask(BackupDto backup, boolean latest) {
            this.backup = backup;
            this.latest = latest;
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

    private final MediaGarrdProperties configuration;
    private final ClientStateService clientStateService;
    private final ServerApiService serverApiService;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService scheduledExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean scheduledRunning = new AtomicBoolean();
    private final AtomicBoolean noBackupsReported = new AtomicBoolean();
    private final Map<String, PickupTask> tasks = new LinkedHashMap<>();
    private final PickupSchedule schedule;

    @Autowired
    public BackupPickupService(MediaGarrdProperties configuration, ClientStateService clientStateService, ServerApiService serverApiService) {
        this(configuration, clientStateService, serverApiService, Clock.systemUTC());
    }

    BackupPickupService(MediaGarrdProperties configuration, ClientStateService clientStateService, ServerApiService serverApiService, Clock clock) {
        this.configuration = configuration;
        this.clientStateService = clientStateService;
        this.serverApiService = serverApiService;
        this.clock = clock;
        this.schedule = new PickupSchedule(clock);
        clientStateService.addActiveServerListener(server -> noBackupsReported.set(false));
    }

    @PostConstruct
    void removeStalePartFilesAtStartup() {
        removeStalePartFiles(downloadsDirectory());
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduledPickup() {
        if (!paired() || !schedule.due(clientStateService.getLastPickupAt(), clientStateService.getPickupInterval())
            || !scheduledRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduledExecutor.execute(this::runScheduledPickup);
        } catch (RejectedExecutionException e) {
            scheduledRunning.set(false);
        }
    }

    void runScheduledPickup() {
        try {
            Path saved = pickupLatestAuto();
            noBackupsReported.set(false);
            if (saved == null) {
                LOGGER.debug("Scheduled pickup deferred, the latest backup is being downloaded by a manual pickup");
            } else {
                LOGGER.info("Scheduled pickup saved {}, next pickup at {}", saved, nextPickup());
            }
        } catch (NoBackupsException e) {
            Instant retryAt = schedule.failed();
            if (noBackupsReported.compareAndSet(false, true)) {
                LOGGER.info("The server has no backups yet, checking again at {}. Further checks are logged at DEBUG until a backup exists", retryAt);
            } else {
                LOGGER.debug("The server still has no backups, checking again at {}", retryAt);
            }
        } catch (RuntimeException e) {
            noBackupsReported.set(false);
            LOGGER.warn("Scheduled pickup failed, next attempt at {}: {}", schedule.failed(), e.getMessage());
        } finally {
            scheduledRunning.set(false);
        }
    }

    public Duration updatePickupInterval(Duration interval) {
        Duration updated = clientStateService.setPickupInterval(interval);
        schedule.reset();
        return updated;
    }

    public Instant nextPickup() {
        return schedule.dueAt(clientStateService.getLastPickupAt(), clientStateService.getPickupInterval());
    }

    boolean scheduledRunning() {
        return scheduledRunning.get();
    }

    private boolean paired() {
        String server = clientStateService.getActiveServer();
        return server != null && !server.isBlank() && clientStateService.hasPairing(server);
    }

    private void pickupSucceeded(String server) {
        try {
            if (clientStateService.recordPickup(server, clock.instant())) {
                schedule.reset();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not record the pickup time, next attempt at {}: {}", schedule.failed(), e.getMessage());
        }
    }

    public PickupStartDto startPickupLatestTask() {
        String server = clientStateService.requireActiveServer();
        return startPickupTask(server, latest(serverApiService.listBackups(server)), true);
    }

    public PickupStartDto startPickupByIdTask(String backupId) {
        String server = clientStateService.requireActiveServer();
        List<BackupDto> backups = serverApiService.listBackups(server);
        BackupDto backup = backups.stream()
            .filter(candidate -> candidate.id().equals(backupId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Backup not found: " + backupId));
        return startPickupTask(server, backup, backup.equals(backups.getFirst()));
    }

    public synchronized PickupProgressDto pickupProgress(String taskId) {
        PickupTask task = tasks.get(taskId);
        if (task == null) {
            throw new NotFoundException("Pickup task not found: " + taskId);
        }
        return task.progress();
    }

    private synchronized PickupStartDto startPickupTask(String server, BackupDto backup, boolean latest) {
        requireArchiveFileName(backup.fileName());
        evictFinishedTasks();
        PickupTask running = tasks.values().stream()
            .filter(task -> !task.finished() && task.backup.id().equals(backup.id()))
            .findFirst()
            .orElse(null);
        if (running != null) {
            LOGGER.info("Backup {} is already being downloaded by task {}", backup.id(), running.taskId);
            return running.started();
        }

        Path downloads = downloadsDirectory();
        removeStalePartFiles(downloads);
        Path destination = inside(downloads, backup.fileName());
        Path existing = existingCopy(downloads, backup);
        PickupTask task = new PickupTask(backup, latest);
        tasks.put(task.taskId, task);
        if (existing != null) {
            LOGGER.info("Backup {} is already downloaded at {}", backup.id(), existing);
            task.complete(existing);
            if (latest) {
                pickupSucceeded(server);
            }
        } else {
            try {
                executor.execute(() -> download(server, destination, task));
            } catch (RejectedExecutionException e) {
                task.fail(GENERIC_FAILURE);
            }
        }
        return task.started();
    }

    private void download(String server, Path destination, PickupTask task) {
        try {
            transfer(server, destination, task, task.latest ? () -> pickupSucceeded(server) : null);
        } catch (RuntimeException e) {
            LOGGER.warn("Pickup task {} failed for backup {}: {}", task.taskId, task.backup.id(), e.getMessage());
        }
    }

    private Path transfer(String server, Path destination, PickupTask task, Runnable afterSaved) {
        task.status = PickupStatus.RUNNING;
        try {
            Path saved = serverApiService.downloadById(server, task.backup.id(), destination, (written, total) -> {
                task.bytesWritten = written;
                if (total > 0) {
                    task.totalBytes = total;
                }
            });
            if (afterSaved != null) {
                afterSaved.run();
            }
            task.complete(saved);
            LOGGER.info("Pickup task {} saved {}", task.taskId, saved);
            return saved;
        } catch (RuntimeException e) {
            task.fail(userMessage(e));
            throw e;
        }
    }

    private static String userMessage(RuntimeException e) {
        if (e instanceof DownloadFailedException || e instanceof PairingRequiredException || e instanceof ServerRedirectException) {
            return e.getMessage();
        }
        if (e instanceof RestClientResponseException response) {
            return "MediaGarrd server responded with " + response.getStatusCode().value();
        }
        if (e instanceof ResourceAccessException) {
            return "MediaGarrd server is unreachable";
        }
        return GENERIC_FAILURE;
    }

    private void evictFinishedTasks() {
        Instant cutoff = clock.instant().minus(FINISHED_TASK_RETENTION);
        tasks.values().removeIf(task -> task.finished() && task.finishedAt.isBefore(cutoff));
    }

    private Path pickupLatestAuto() {
        String server = clientStateService.requireActiveServer();
        List<BackupDto> backups = serverApiService.listBackups(server);
        BackupDto latest = latest(backups);
        requireArchiveFileName(latest.fileName());
        Path downloads = downloadsDirectory();
        removeStalePartFiles(downloads);
        Path existing = existingCopy(downloads, latest);
        if (existing != null) {
            LOGGER.info("Scheduled pickup skipped, backup {} is already downloaded at {}", latest.id(), existing);
            pickupSucceeded(server);
            return existing;
        }

        Path destination = inside(downloads, autoFileName(latest));
        ScheduledRegistration registration = registerScheduledTask(downloads, latest);
        if (registration.existing() != null) {
            pickupSucceeded(server);
            return registration.existing();
        }
        PickupTask task = registration.task();
        if (task == null) {
            return null;
        }
        return transfer(server, destination, task, () -> {
            removeSuperseded(downloads, backups, latest);
            pickupSucceeded(server);
        });
    }

    private record ScheduledRegistration(PickupTask task, Path existing) {
    }

    private synchronized ScheduledRegistration registerScheduledTask(Path downloads, BackupDto latest) {
        evictFinishedTasks();
        Path existing = existingCopy(downloads, latest);
        if (existing != null) {
            return new ScheduledRegistration(null, existing);
        }
        boolean running = tasks.values().stream().anyMatch(task -> !task.finished() && task.backup.id().equals(latest.id()));
        if (running) {
            return new ScheduledRegistration(null, null);
        }
        PickupTask task = new PickupTask(latest, true);
        tasks.put(task.taskId, task);
        return new ScheduledRegistration(task, null);
    }

    private synchronized void removeStalePartFiles(Path downloads) {
        if (!Files.isDirectory(downloads)) {
            return;
        }
        Set<String> inProgress = tasks.values().stream()
            .filter(task -> !task.finished())
            .flatMap(task -> Stream.of(task.backup.fileName(), autoFileName(task.backup)))
            .map(name -> name + PART_SUFFIX)
            .collect(Collectors.toSet());
        try (Stream<Path> entries = Files.list(downloads)) {
            entries
                .filter(path -> path.getFileName().toString().endsWith(PART_SUFFIX))
                .filter(path -> !inProgress.contains(path.getFileName().toString()))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .forEach(path -> {
                    if (QuietFiles.delete(path)) {
                        LOGGER.info("Removed incomplete download {}", path.getFileName());
                    }
                });
        } catch (IOException e) {
            LOGGER.warn("Could not look for incomplete downloads in {}: {}", downloads, e.getMessage());
        }
    }

    private Path existingCopy(Path downloads, BackupDto backup) {
        return Stream.of(autoFileName(backup), backup.fileName())
            .map(name -> inside(downloads, name))
            .filter(path -> hasSize(path, backup.sizeBytes()))
            .findFirst()
            .orElse(null);
    }

    private static void removeSuperseded(Path downloads, List<BackupDto> backups, BackupDto current) {
        try {
            backups.stream()
                .skip(1)
                .filter(backup -> !backup.id().equals(current.id()))
                .map(backup -> autoFileName(backup))
                .filter(name -> name.endsWith(AUTO_DOWNLOAD_SUFFIX + ZIP_SUFFIX))
                .map(name -> downloads.resolve(name).normalize())
                .filter(path -> downloads.equals(path.getParent()))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .forEach(path -> {
                    if (QuietFiles.delete(path)) {
                        LOGGER.info("Removed superseded download {}", path.getFileName());
                    }
                });
        } catch (RuntimeException e) {
            LOGGER.warn("Could not remove superseded downloads in {}: {}", downloads, e.getMessage());
        }
    }

    private static void requireArchiveFileName(String fileName) {
        if (!fileName.endsWith(ZIP_SUFFIX) || fileName.endsWith(PART_SUFFIX)) {
            throw new InvalidRequestException("Backup file name must end in " + ZIP_SUFFIX + ": " + fileName);
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

    private static BackupDto latest(List<BackupDto> backups) {
        if (backups.isEmpty()) {
            throw new NoBackupsException();
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
            throw new InvalidRequestException("Backup file name must not leave the download directory: " + fileName);
        }
        return path;
    }

    private Path downloadsDirectory() {
        return Path.of(configuration.getDownloadDirectory()).toAbsolutePath().normalize();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }
}
