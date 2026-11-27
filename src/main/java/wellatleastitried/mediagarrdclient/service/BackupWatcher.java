package wellatleastitried.mediagarrdclient.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import wellatleastitried.mediagarrdclient.dto.BackupDto;

final class BackupWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupWatcher.class);

    private final ServerApiService serverApiService;
    private final DashboardEvents events;
    private final Clock clock;
    private final Object lock;
    private final Duration interval;
    private final Supplier<String> server;
    private final LongSupplier epoch;
    private final BooleanSupplier degraded;
    private final CoalescedRefresh refresh;
    private List<BackupDto> seen;
    private Instant checkedAt;

    BackupWatcher(
        ServerApiService serverApiService,
        DashboardEvents events,
        Clock clock,
        Object lock,
        Executor executor,
        Duration interval,
        Supplier<String> server,
        LongSupplier epoch,
        BooleanSupplier degraded
    ) {
        this.serverApiService = serverApiService;
        this.events = events;
        this.clock = clock;
        this.lock = lock;
        this.interval = interval;
        this.server = server;
        this.epoch = epoch;
        this.degraded = degraded;
        this.refresh = new CoalescedRefresh(lock, executor, this::fetch);
    }

    void forget() {
        seen = null;
        checkedAt = null;
    }

    void forgetListing() {
        seen = null;
    }

    void requestWhenDue() {
        if (!refresh.busy() && (checkedAt == null || Duration.between(checkedAt, clock.instant()).compareTo(interval) >= 0)) {
            refresh.request();
        }
    }

    private void fetch() {
        String target;
        long expected;
        synchronized (lock) {
            target = server.get();
            expected = epoch.getAsLong();
        }
        if (target == null) {
            return;
        }
        List<BackupDto> listed = list(target);
        synchronized (lock) {
            if (expected != epoch.getAsLong() || !degraded.getAsBoolean()) {
                return;
            }
            record(listed);
        }
    }

    private List<BackupDto> list(String target) {
        try {
            return serverApiService.listBackups(target);
        } catch (RuntimeException e) {
            LOGGER.debug("{} did not list its backups: {}", target, e.getMessage());
            return null;
        }
    }

    private void record(List<BackupDto> listed) {
        checkedAt = clock.instant();
        if (listed == null) {
            return;
        }
        if (seen != null && !listed.equals(seen)) {
            events.publish("backups-changed", Map.of("at", clock.instant().toString()));
        }
        seen = listed;
    }
}
