package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T12:00:00Z"));
    private final SessionService sessions = new SessionService(clock, Duration.ofHours(1), Duration.ofHours(5), 3);

    @Test
    void tellsListenersWhichSessionEndedOnLogoutClearAndExpiry() {
        java.util.List<String> ended = new java.util.ArrayList<>();
        sessions.addEndedListener(ended::add);
        String first = sessions.create();
        String second = sessions.create();
        String third = sessions.create();

        sessions.invalidate(first);
        assertEquals(java.util.List.of(SessionService.keyOf(first)), ended);
        assertFalse(sessions.validate(first));
        assertTrue(sessions.validate(third));
        sessions.invalidate(first);
        assertEquals(1, ended.size());

        clock.advance(Duration.ofHours(2));
        assertFalse(sessions.validate(second));
        assertEquals(SessionService.keyOf(second), ended.get(1));

        sessions.clear();
        assertEquals(java.util.List.of(SessionService.keyOf(first), SessionService.keyOf(second), SessionService.keyOf(third)), ended);
        assertFalse(sessions.validate(third));
    }

    @Test
    void reportsWhetherASessionIsStillActiveWithoutExtendingIt() {
        String token = sessions.create();
        String key = SessionService.keyOf(token);

        clock.advance(Duration.ofMinutes(59));
        assertTrue(sessions.isActive(key));
        clock.advance(Duration.ofMinutes(2));

        assertFalse(sessions.isActive(key));
        assertFalse(sessions.isActive(null));
        assertFalse(sessions.isActive("unknown"));
    }

    @Test
    void acceptsOnlySessionsItCreated() {
        String token = sessions.create();

        assertTrue(sessions.validate(token));
        assertFalse(sessions.validate(token + "x"));
        assertFalse(sessions.validate(""));
        assertFalse(sessions.validate(null));
        assertEquals(43, token.length());
    }

    @Test
    void sessionsExpireAfterBeingIdle() {
        String token = sessions.create();

        clock.advance(Duration.ofMinutes(59));
        assertTrue(sessions.validate(token));
        clock.advance(Duration.ofMinutes(59));
        assertTrue(sessions.validate(token));
        clock.advance(Duration.ofMinutes(61));
        assertFalse(sessions.validate(token));
    }

    @Test
    void sessionsExpireAfterTheAbsoluteLimitEvenWhenActive() {
        String token = sessions.create();

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(59));
            assertTrue(sessions.validate(token));
        }
        clock.advance(Duration.ofMinutes(59));
        assertFalse(sessions.validate(token));
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfSessionsDroppingTheOldest() {
        String a = sessions.create();
        String b = sessions.create();
        String c = sessions.create();
        String d = sessions.create();

        assertFalse(sessions.validate(a));
        assertTrue(sessions.validate(b));
        assertTrue(sessions.validate(c));
        assertTrue(sessions.validate(d));
        assertNotEquals(b, c);
    }
}
