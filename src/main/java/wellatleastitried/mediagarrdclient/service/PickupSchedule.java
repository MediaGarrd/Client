package wellatleastitried.mediagarrdclient.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class PickupSchedule {

    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15));

    private final Clock clock;
    private int failures;
    private Instant retryAt;

    PickupSchedule(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean due(Instant lastPickup, Duration interval) {
        Instant now = clock.instant();
        if (retryAt != null) {
            Instant cap = now.plus(BACKOFF.getLast());
            if (retryAt.isAfter(cap)) {
                retryAt = cap;
            }
            if (now.isBefore(retryAt)) {
                return false;
            }
        }
        return !now.isBefore(dueAt(lastPickup, interval));
    }

    Instant dueAt(Instant lastPickup, Duration interval) {
        Instant now = clock.instant();
        return lastPickup == null || lastPickup.isAfter(now) ? now : lastPickup.plus(interval);
    }

    synchronized Instant failed() {
        retryAt = clock.instant().plus(BACKOFF.get(Math.min(failures, BACKOFF.size() - 1)));
        failures++;
        return retryAt;
    }

    synchronized void reset() {
        failures = 0;
        retryAt = null;
    }
}
