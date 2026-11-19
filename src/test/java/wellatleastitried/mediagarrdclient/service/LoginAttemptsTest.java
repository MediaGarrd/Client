package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class LoginAttemptsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T12:00:00Z"));
    private final LoginAttempts attempts = new LoginAttempts(clock, 3);

    @Test
    void allowsAFewFailuresThenLocksWithAGrowingDelay() {
        for (int i = 0; i < LoginAttempts.FREE_FAILURES; i++) {
            attempts.failed("10.0.0.1");
            assertTrue(attempts.lockedFor("10.0.0.1").isEmpty());
        }

        attempts.failed("10.0.0.1");
        assertEquals(Duration.ofSeconds(5), attempts.lockedFor("10.0.0.1").orElseThrow());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(attempts.lockedFor("10.0.0.1").isEmpty());
        attempts.failed("10.0.0.1");
        assertEquals(Duration.ofSeconds(10), attempts.lockedFor("10.0.0.1").orElseThrow());
    }

    @Test
    void capsTheDelay() {
        for (int i = 0; i < 40; i++) {
            attempts.failed("10.0.0.1");
            clock.advance(LoginAttempts.MAX_LOCK);
        }

        attempts.failed("10.0.0.1");

        assertEquals(LoginAttempts.MAX_LOCK, attempts.lockedFor("10.0.0.1").orElseThrow());
    }

    @Test
    void trackSourcesSeparatelyAndForgetAfterASuccess() {
        for (int i = 0; i < 5; i++) {
            attempts.failed("10.0.0.1");
        }

        assertTrue(attempts.lockedFor("10.0.0.1").isPresent());
        assertTrue(attempts.lockedFor("10.0.0.2").isEmpty());
        attempts.succeeded("10.0.0.1");
        assertTrue(attempts.lockedFor("10.0.0.1").isEmpty());
    }

    @Test
    void forgetsOldFailures() {
        for (int i = 0; i < 5; i++) {
            attempts.failed("10.0.0.1");
        }

        clock.advance(LoginAttempts.FORGET_AFTER);

        assertTrue(attempts.lockedFor("10.0.0.1").isEmpty());
        attempts.failed("10.0.0.1");
        assertTrue(attempts.lockedFor("10.0.0.1").isEmpty());
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfSources() {
        for (int i = 0; i < 4; i++) {
            for (int n = 0; n < 5; n++) {
                attempts.failed("10.0.0." + i);
            }
        }

        assertTrue(attempts.lockedFor("10.0.0.0").isEmpty());
        assertTrue(attempts.lockedFor("10.0.0.3").isPresent());
    }

    @Test
    void refusesASecondAttemptFromASourceWhileOneIsInFlight() {
        assertTrue(attempts.tryBegin("10.0.0.1").isEmpty());
        assertTrue(attempts.tryBegin("10.0.0.1").isPresent());
        assertTrue(attempts.tryBegin("10.0.0.2").isEmpty());

        attempts.failed("10.0.0.1");

        assertTrue(attempts.tryBegin("10.0.0.1").isEmpty());
        attempts.abandon("10.0.0.1");
        assertTrue(attempts.tryBegin("10.0.0.1").isEmpty());
    }

    @Test
    void lockoutFromFailuresIsHonouredByTryBegin() {
        for (int i = 0; i < LoginAttempts.FREE_FAILURES + 1; i++) {
            attempts.tryBegin("10.0.0.1");
            attempts.failed("10.0.0.1");
        }

        assertEquals(Duration.ofSeconds(5), attempts.tryBegin("10.0.0.1").orElseThrow());
    }

    private void drainBucket() {
        for (int i = 0; i < LoginAttempts.BUCKET_CAPACITY; i++) {
            assertTrue(attempts.tryBegin("10.9.0." + i).isEmpty());
        }
    }

    @Test
    void anEmptyBucketRefusesEverySourceWithTheTimeToTheNextTokenInsteadOfLockingAnyone() {
        drainBucket();

        assertEquals(Duration.ofSeconds(2), attempts.tryBegin("10.8.0.1").orElseThrow());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(1), attempts.tryBegin("10.8.0.1").orElseThrow());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(attempts.tryBegin("10.8.0.1").isEmpty());
        assertTrue(attempts.tryBegin("10.8.0.2").isPresent());
    }

    @Test
    void aRefusedAttemptDoesNotLeaveASourceReserved() {
        drainBucket();

        assertTrue(attempts.tryBegin("10.8.0.1").isPresent());
        clock.advance(Duration.ofSeconds(2));

        assertTrue(attempts.tryBegin("10.8.0.1").isEmpty());
    }

    @Test
    void aSuccessfulLoginGivesItsTokenBackButNeverBeyondTheCapacity() {
        assertTrue(attempts.tryBegin("10.7.0.1").isEmpty());
        attempts.succeeded("10.7.0.1");
        attempts.succeeded("10.7.0.1");
        attempts.succeeded("10.7.0.2");
        for (int i = 0; i < LoginAttempts.BUCKET_CAPACITY; i++) {
            assertTrue(attempts.tryBegin("10.6.0." + i).isEmpty(), "attempt " + i);
        }

        assertTrue(attempts.tryBegin("10.6.1.1").isPresent());
    }

    @Test
    void anAbandonedAttemptGivesItsTokenBack() {
        drainBucket();
        clock.advance(Duration.ofSeconds(2));
        assertTrue(attempts.tryBegin("10.5.0.1").isEmpty());
        assertTrue(attempts.tryBegin("10.5.0.2").isPresent());

        attempts.abandon("10.5.0.1");

        assertTrue(attempts.tryBegin("10.5.0.2").isEmpty());
    }

    @Test
    void aFailureKeepsItsTokenSpent() {
        assertTrue(attempts.tryBegin("10.4.0.1").isEmpty());
        attempts.failed("10.4.0.1");
        for (int i = 0; i < LoginAttempts.BUCKET_CAPACITY - 1; i++) {
            assertTrue(attempts.tryBegin("10.3.0." + i).isEmpty(), "attempt " + i);
        }

        assertTrue(attempts.tryBegin("10.3.1.1").isPresent());
    }

    @Test
    void aRefusalWhileBusyDoesNotSpendAToken() {
        assertTrue(attempts.tryBegin("10.2.0.1").isEmpty());
        for (int i = 0; i < 100; i++) {
            assertEquals(LoginAttempts.BUSY_RETRY, attempts.tryBegin("10.2.0.1").orElseThrow());
        }
        attempts.failed("10.2.0.1");
        for (int i = 0; i < LoginAttempts.BUCKET_CAPACITY - 1; i++) {
            assertTrue(attempts.tryBegin("10.1.0." + i).isEmpty(), "attempt " + i);
        }

        assertTrue(attempts.tryBegin("10.1.1.1").isPresent());
    }

    @Test
    void aSucceededCallWithoutAReservationDoesNotRefund() {
        drainBucket();

        attempts.succeeded("10.0.9.9");
        attempts.abandon("10.0.9.9");

        assertTrue(attempts.tryBegin("10.8.0.1").isPresent());
    }
}
