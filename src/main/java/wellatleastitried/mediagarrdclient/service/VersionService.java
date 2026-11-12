package wellatleastitried.mediagarrdclient.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.dto.VersionsDto;

@Service
public class VersionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersionService.class);
    static final Duration REFRESH_INTERVAL = Duration.ofHours(1);
    static final Duration RETRY_INTERVAL = Duration.ofMinutes(10);
    static final Duration PENDING_RETRY_INTERVAL = Duration.ofSeconds(30);
    static final Duration ERROR_RETRY_INTERVAL = Duration.ofMinutes(5);
    static final Duration RECENT_SUCCESS = Duration.ofMinutes(5);
    private static final VersionStatusDto SERVER_UNAVAILABLE =
        new VersionStatusDto(null, null, null, false, CheckState.UNAVAILABLE, null, "Server version is unavailable");
    private static final VersionStatusDto SERVER_PENDING =
        new VersionStatusDto(null, null, null, false, CheckState.NOT_CHECKED, null, null);

    private final UpdateCheckService updateCheckService;
    private final ServerApiService serverApiService;
    private final Clock clock;
    private final Object lock = new Object();
    private String selected;
    private VersionStatusDto server;
    private Instant fetchedAt;
    private Instant lastSuccess;

    @Autowired
    public VersionService(UpdateCheckService updateCheckService, ServerApiService serverApiService) {
        this(updateCheckService, serverApiService, Clock.systemUTC());
    }

    VersionService(UpdateCheckService updateCheckService, ServerApiService serverApiService, Clock clock) {
        this.updateCheckService = updateCheckService;
        this.serverApiService = serverApiService;
        this.clock = clock;
    }

    public VersionsDto versions() {
        synchronized (lock) {
            return new VersionsDto(updateCheckService.status(), server);
        }
    }

    public boolean select(String serverUrl) {
        String next = serverUrl == null || serverUrl.isBlank() ? null : serverUrl;
        synchronized (lock) {
            if (Objects.equals(next, selected)) {
                return false;
            }
            selected = next;
            server = next == null ? null : SERVER_PENDING;
            fetchedAt = null;
            lastSuccess = null;
            return true;
        }
    }

    public boolean invalidate(boolean force) {
        synchronized (lock) {
            if (selected == null) {
                return false;
            }
            if (!force && lastSuccess != null && Duration.between(lastSuccess, clock.instant()).compareTo(RECENT_SUCCESS) < 0) {
                return false;
            }
            fetchedAt = null;
            return true;
        }
    }

    public boolean markUnreachable() {
        synchronized (lock) {
            if (selected == null || server != SERVER_PENDING) {
                return false;
            }
            server = SERVER_UNAVAILABLE;
            fetchedAt = clock.instant();
            return true;
        }
    }

    public boolean needsRefresh() {
        synchronized (lock) {
            if (selected == null) {
                return false;
            }
            if (fetchedAt == null) {
                return true;
            }
            return Duration.between(fetchedAt, clock.instant()).compareTo(limitFor(server)) >= 0;
        }
    }

    private static Duration limitFor(VersionStatusDto status) {
        if (status == null) {
            return REFRESH_INTERVAL;
        }
        return switch (status.checkState()) {
            case NOT_CHECKED -> PENDING_RETRY_INTERVAL;
            case ERROR -> ERROR_RETRY_INTERVAL;
            case UNAVAILABLE -> RETRY_INTERVAL;
            default -> REFRESH_INTERVAL;
        };
    }

    public boolean refresh() {
        String url;
        synchronized (lock) {
            url = selected;
        }
        if (url == null) {
            return false;
        }
        VersionStatusDto fetched = fetch(url);
        synchronized (lock) {
            if (!url.equals(selected)) {
                return false;
            }
            boolean changed = !fetched.equals(server);
            server = fetched;
            fetchedAt = clock.instant();
            if (fetched.checkState() == CheckState.OK || fetched.checkState() == CheckState.NO_RELEASES) {
                lastSuccess = fetchedAt;
            }
            return changed;
        }
    }

    private VersionStatusDto fetch(String serverUrl) {
        try {
            VersionStatusDto version = serverApiService.serverVersion(serverUrl);
            return version == null ? SERVER_UNAVAILABLE : version;
        } catch (RuntimeException e) {
            LOGGER.debug("Version of {} is unavailable: {}", serverUrl, e.getMessage());
            return SERVER_UNAVAILABLE;
        }
    }
}
