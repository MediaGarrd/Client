package wellatleastitried.mediagarrdclient.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LoginAttempts {

    static final int FREE_FAILURES = 3;
    static final Duration FIRST_LOCK = Duration.ofSeconds(5);
    static final Duration MAX_LOCK = Duration.ofMinutes(5);
    static final Duration FORGET_AFTER = Duration.ofHours(1);
    static final int MAX_TRACKED = 1000;
    static final int BUCKET_CAPACITY = 30;
    static final Duration BUCKET_REFILL_PERIOD = Duration.ofMinutes(1);
    public static final Duration BUSY_RETRY = Duration.ofSeconds(1);

    private static final class Record {
        private int failures;
        private Instant lockedUntil = Instant.MIN;
        private Instant lastFailure = Instant.MIN;
    }

    private final Clock clock;
    private final int maxTracked;
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private double tokens = BUCKET_CAPACITY;
    private Instant lastRefill;

    @Autowired
    public LoginAttempts() {
        this(Clock.systemUTC(), MAX_TRACKED);
    }

    LoginAttempts(Clock clock, int maxTracked) {
        this.clock = clock;
        this.maxTracked = maxTracked;
        this.lastRefill = clock.instant();
    }

    public synchronized Optional<Duration> tryBegin(String source) {
        Optional<Duration> locked = lockedFor(source);
        if (locked.isPresent()) {
            return locked;
        }
        if (!inFlight.add(source)) {
            return Optional.of(BUSY_RETRY);
        }
        refill();
        if (tokens < 1) {
            inFlight.remove(source);
            double missing = 1 - tokens;
            return Optional.of(Duration.ofNanos((long) Math.ceil(missing * BUCKET_REFILL_PERIOD.toNanos() / BUCKET_CAPACITY)));
        }
        tokens -= 1;
        return Optional.empty();
    }

    public synchronized void abandon(String source) {
        if (inFlight.remove(source)) {
            refund();
        }
    }

    private void refill() {
        Instant now = clock.instant();
        if (now.isAfter(lastRefill)) {
            double elapsed = Duration.between(lastRefill, now).toNanos();
            tokens = Math.min(BUCKET_CAPACITY, tokens + elapsed * BUCKET_CAPACITY / BUCKET_REFILL_PERIOD.toNanos());
            lastRefill = now;
        }
    }

    private void refund() {
        refill();
        tokens = Math.min(BUCKET_CAPACITY, tokens + 1);
    }

    public synchronized Optional<Duration> lockedFor(String source) {
        Record record = records.get(source);
        if (record == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (Duration.between(record.lastFailure, now).compareTo(FORGET_AFTER) >= 0) {
            records.remove(source);
            return Optional.empty();
        }
        if (now.isBefore(record.lockedUntil)) {
            return Optional.of(Duration.between(now, record.lockedUntil));
        }
        return Optional.empty();
    }

    public synchronized void failed(String source) {
        Instant now = clock.instant();
        inFlight.remove(source);
        Record record = records.remove(source);
        if (record == null || Duration.between(record.lastFailure, now).compareTo(FORGET_AFTER) >= 0) {
            record = new Record();
        }
        while (records.size() >= maxTracked) {
            records.remove(records.keySet().iterator().next());
        }
        record.failures++;
        record.lastFailure = now;
        if (record.failures > FREE_FAILURES) {
            long doublings = Math.min(record.failures - FREE_FAILURES - 1, 20);
            Duration lock = FIRST_LOCK.multipliedBy(1L << doublings);
            record.lockedUntil = now.plus(lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock);
        }
        records.put(source, record);
    }

    public synchronized void succeeded(String source) {
        if (inFlight.remove(source)) {
            refund();
        }
        records.remove(source);
    }
}
