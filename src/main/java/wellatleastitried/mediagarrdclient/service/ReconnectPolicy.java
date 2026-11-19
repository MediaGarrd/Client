package wellatleastitried.mediagarrdclient.service;

import java.time.Duration;

final class ReconnectPolicy {

    enum Outcome {
        ROLLOVER,
        UNSUPPORTED,
        REJECTED,
        FAILED
    }

    record Decision(Outcome outcome, Duration delay) {
    }

    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration unsupportedRetry;
    private final Duration minLifetime;
    private Duration backoff;

    ReconnectPolicy(Duration initialBackoff, Duration maxBackoff, Duration unsupportedRetry, Duration minLifetime) {
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.unsupportedRetry = unsupportedRetry;
        this.minLifetime = minLifetime;
        this.backoff = initialBackoff;
    }

    Decision next(Outcome outcome, boolean eventsSeen, long lifetimeNanos) {
        boolean shortLived = lifetimeNanos < minLifetime.toNanos();
        if (eventsSeen && !shortLived) {
            backoff = initialBackoff;
        }
        Outcome effective = outcome == Outcome.ROLLOVER && shortLived ? Outcome.FAILED : outcome;
        Duration delay = effective == Outcome.UNSUPPORTED ? unsupportedRetry : backoff;
        if (effective == Outcome.FAILED) {
            backoff = min(backoff.multipliedBy(2), maxBackoff);
        }
        return new Decision(effective, delay);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
