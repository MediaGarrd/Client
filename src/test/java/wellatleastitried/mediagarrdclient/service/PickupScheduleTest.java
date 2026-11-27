package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class PickupScheduleTest {

    private static final Duration INTERVAL = Duration.ofHours(12);

    private final MutableClock clock = new MutableClock();
    private final PickupSchedule schedule = new PickupSchedule(clock);

    @Test
    void aClientThatNeverPickedUpIsDueNow() {
        assertTrue(schedule.due(null, INTERVAL));
    }

    @Test
    void aPickupIsDueOneIntervalAfterTheLastOne() {
        Instant last = clock.instant().minus(Duration.ofHours(5));

        assertFalse(schedule.due(last, INTERVAL));
        assertEquals(last.plus(INTERVAL), schedule.dueAt(last, INTERVAL));
        clock.advance(Duration.ofHours(7));
        assertTrue(schedule.due(last, INTERVAL));
    }

    @Test
    void aLastPickupInTheFutureDoesNotStallTheSchedule() {
        assertTrue(schedule.due(clock.instant().plus(Duration.ofDays(30)), INTERVAL));
    }

    @Test
    void failuresBackOffAndCapThenResetOnSuccess() {
        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(15)};
        for (Duration wait : expected) {
            Instant retry = schedule.failed();
            assertEquals(clock.instant().plus(wait), retry);
            assertFalse(schedule.due(null, INTERVAL));
            clock.advance(wait);
            assertTrue(schedule.due(null, INTERVAL));
        }

        schedule.reset();
        assertTrue(schedule.due(null, INTERVAL));
        assertEquals(clock.instant().plus(Duration.ofMinutes(1)), schedule.failed());
    }

    @Test
    void aClockThatJumpsBackwardDoesNotStallTheRetry() {
        schedule.failed();
        schedule.failed();
        schedule.failed();
        schedule.failed();
        schedule.failed();
        clock.advance(Duration.ofHours(-5));

        assertFalse(schedule.due(null, INTERVAL));
        clock.advance(Duration.ofMinutes(15));
        assertTrue(schedule.due(null, INTERVAL));
    }
}
