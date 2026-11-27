package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import wellatleastitried.mediagarrdclient.service.ReconnectPolicy.Outcome;

class ReconnectPolicyTest {

    private static final long LONG = Duration.ofSeconds(60).toNanos();
    private static final long SHORT = Duration.ofSeconds(1).toNanos();

    private final ReconnectPolicy policy = new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(8), Duration.ofMinutes(5), Duration.ofSeconds(5));

    @Test
    void failuresBackOffExponentiallyUpToTheMaximum() {
        assertEquals(Duration.ofSeconds(1), policy.next(Outcome.FAILED, false, SHORT).delay());
        assertEquals(Duration.ofSeconds(2), policy.next(Outcome.FAILED, false, SHORT).delay());
        assertEquals(Duration.ofSeconds(4), policy.next(Outcome.FAILED, false, SHORT).delay());
        assertEquals(Duration.ofSeconds(8), policy.next(Outcome.FAILED, false, SHORT).delay());
        assertEquals(Duration.ofSeconds(8), policy.next(Outcome.FAILED, false, SHORT).delay());
    }

    @Test
    void aLongStreamThatDeliveredEventsResetsTheBackoff() {
        policy.next(Outcome.FAILED, false, SHORT);
        policy.next(Outcome.FAILED, false, SHORT);

        assertEquals(Duration.ofSeconds(1), policy.next(Outcome.FAILED, true, LONG).delay());
    }

    @Test
    void aShortStreamThatDeliveredEventsDoesNotResetTheBackoff() {
        policy.next(Outcome.FAILED, false, SHORT);

        assertEquals(Duration.ofSeconds(2), policy.next(Outcome.FAILED, true, SHORT).delay());
    }

    @Test
    void aRolloverReconnectsAtOnceUnlessItWasShortLived() {
        assertEquals(Outcome.ROLLOVER, policy.next(Outcome.ROLLOVER, true, LONG).outcome());
        assertEquals(Outcome.FAILED, policy.next(Outcome.ROLLOVER, true, SHORT).outcome());
    }

    @Test
    void anUnsupportedServerIsRetriedAfterTheLongDelayWithoutGrowingTheBackoff() {
        assertEquals(Duration.ofMinutes(5), policy.next(Outcome.UNSUPPORTED, false, SHORT).delay());
        assertEquals(Duration.ofSeconds(1), policy.next(Outcome.FAILED, false, SHORT).delay());
    }

    @Test
    void aRejectionIsPassedThrough() {
        assertEquals(Outcome.REJECTED, policy.next(Outcome.REJECTED, false, SHORT).outcome());
    }
}
