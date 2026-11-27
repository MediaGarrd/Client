package wellatleastitried.mediagarrdclient.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionService.class);
    static final Duration IDLE_TIMEOUT = Duration.ofHours(12);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofDays(7);
    static final int MAX_SESSIONS = 100;
    private static final int TOKEN_BYTES = 32;

    private static final class Session {
        private final Instant createdAt;
        private Instant lastSeen;

        private Session(Instant now) {
            this.createdAt = now;
            this.lastSeen = now;
        }
    }

    private final Clock clock;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final int maxSessions;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final List<Consumer<String>> endedListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public SessionService() {
        this(Clock.systemUTC(), IDLE_TIMEOUT, ABSOLUTE_TIMEOUT, MAX_SESSIONS);
    }

    SessionService(Clock clock, Duration idleTimeout, Duration absoluteTimeout, int maxSessions) {
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
        this.maxSessions = maxSessions;
    }

    public synchronized String create() {
        Instant now = clock.instant();
        evictExpired(now);
        while (sessions.size() >= maxSessions) {
            Iterator<String> oldest = sessions.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(key(token), new Session(now));
        return token;
    }

    public boolean validate(String token) {
        if (token == null || token.isEmpty() || token.length() > 128) {
            return false;
        }
        String key = keyOf(token);
        boolean valid;
        boolean expired = false;
        synchronized (this) {
            Instant now = clock.instant();
            Session session = sessions.get(key);
            if (session == null) {
                return false;
            }
            expired = expired(session, now);
            if (expired) {
                sessions.remove(key);
            } else {
                session.lastSeen = now;
            }
            valid = !expired;
        }
        if (expired) {
            notifyEnded(List.of(key));
        }
        return valid;
    }

    public boolean isActive(String key) {
        if (key == null) {
            return false;
        }
        boolean expired = false;
        boolean active;
        synchronized (this) {
            Session session = sessions.get(key);
            if (session == null) {
                return false;
            }
            expired = expired(session, clock.instant());
            if (expired) {
                sessions.remove(key);
            }
            active = !expired;
        }
        if (expired) {
            notifyEnded(List.of(key));
        }
        return active;
    }

    public void invalidate(String token) {
        if (token == null) {
            return;
        }
        String key = keyOf(token);
        boolean removed;
        synchronized (this) {
            removed = sessions.remove(key) != null;
        }
        if (removed) {
            notifyEnded(List.of(key));
        }
    }

    public void clear() {
        List<String> keys;
        synchronized (this) {
            keys = List.copyOf(sessions.keySet());
            sessions.clear();
        }
        notifyEnded(keys);
    }

    public void addEndedListener(Consumer<String> listener) {
        endedListeners.add(listener);
    }

    private void notifyEnded(List<String> keys) {
        for (String key : keys) {
            for (Consumer<String> listener : endedListeners) {
                try {
                    listener.accept(key);
                } catch (RuntimeException e) {
                    LOGGER.warn("Session listener failed: {}", e.getMessage());
                }
            }
        }
    }

    private void evictExpired(Instant now) {
        sessions.values().removeIf(session -> expired(session, now));
    }

    private boolean expired(Session session, Instant now) {
        return Duration.between(session.lastSeen, now).compareTo(idleTimeout) >= 0
            || Duration.between(session.createdAt, now).compareTo(absoluteTimeout) >= 0;
    }

    public static String keyOf(String token) {
        return token == null ? null : key(token);
    }

    private static String key(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
