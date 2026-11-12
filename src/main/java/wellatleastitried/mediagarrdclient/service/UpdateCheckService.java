package wellatleastitried.mediagarrdclient.service;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import wellatleastitried.mediagarrdclient.Configuration;
import wellatleastitried.mediagarrdclient.dto.CheckState;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;

@Service
@Lazy(false)
public class UpdateCheckService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GithubRelease(@JsonProperty("tag_name") String tagName, @JsonProperty("html_url") String htmlUrl) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateCheckService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MIN_INTERVAL = Duration.ofHours(1);

    private final String currentVersion;
    private final Optional<SemVer> current;
    private final String repository;
    private final Clock clock;
    private final RestClient client;
    private final AtomicReference<VersionStatusDto> status;
    private final List<Consumer<VersionStatusDto>> listeners = new CopyOnWriteArrayList<>();

    @Autowired
    public UpdateCheckService(Configuration properties, ObjectProvider<BuildProperties> buildProperties) {
        this(properties, versionOf(buildProperties), Clock.systemUTC());
    }

    UpdateCheckService(Configuration properties, String currentVersion, Clock clock) {
        Duration interval = properties.getUpdateCheckInterval();
        if (interval == null || interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalStateException("mediagarrd.client.update-check-interval must be at least " + MIN_INTERVAL + ", was " + interval);
        }
        this.currentVersion = currentVersion;
        this.current = SemVer.parse(currentVersion);
        this.repository = properties.getUpdateRepository();
        this.clock = clock;
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder()
            .baseUrl(properties.getGithubApiBaseUrl().replaceAll("/+$", ""))
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.USER_AGENT, "MediaGarrd-Client/" + (currentVersion == null ? "unknown" : currentVersion))
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .build();
        this.status = new AtomicReference<>(result(CheckState.NOT_CHECKED, null, null, null));
    }

    public VersionStatusDto status() {
        return status.get();
    }

    public void addStatusListener(Consumer<VersionStatusDto> listener) {
        listeners.add(listener);
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${mediagarrd.client.update-check-interval:PT24H}")
    public void check() {
        VersionStatusDto previous = status.get();
        VersionStatusDto next = fetch();
        status.set(next);
        logChange(previous, next);
        if (changed(previous, next)) {
            for (Consumer<VersionStatusDto> listener : listeners) {
                try {
                    listener.accept(next);
                } catch (RuntimeException e) {
                    LOGGER.warn("Update status listener failed: {}", e.getMessage());
                }
            }
        }
    }

    private VersionStatusDto fetch() {
        if (current.isEmpty()) {
            return result(CheckState.ERROR, null, null, "Current version is unknown");
        }
        try {
            GithubRelease release = client.get()
                .uri("/repos/" + repository + "/releases/latest")
                .retrieve()
                .body(GithubRelease.class);
            return fromRelease(release);
        } catch (RestClientResponseException e) {
            return fromHttpFailure(e);
        } catch (ResourceAccessException e) {
            LOGGER.debug("GitHub is unreachable", e);
            return result(CheckState.ERROR, null, null, "GitHub is unreachable");
        } catch (RuntimeException e) {
            LOGGER.debug("Unexpected update check failure", e);
            return result(CheckState.ERROR, null, null, "Unexpected response from GitHub");
        }
    }

    private VersionStatusDto fromRelease(GithubRelease release) {
        Optional<SemVer> latest = release == null ? Optional.empty() : SemVer.parse(release.tagName());
        if (latest.isEmpty()) {
            return result(CheckState.ERROR, null, null, "Latest release has an unrecognized version tag");
        }
        String url = release.htmlUrl() == null || release.htmlUrl().isBlank()
            ? "https://github.com/" + repository + "/releases/tag/" + release.tagName().strip()
            : release.htmlUrl();
        return result(CheckState.OK, latest.get(), url, null);
    }

    private VersionStatusDto fromHttpFailure(RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == HttpStatus.NOT_FOUND.value()) {
            return result(CheckState.NO_RELEASES, null, null, null);
        }
        boolean limitExhausted = code == HttpStatus.FORBIDDEN.value()
            && e.getResponseHeaders() != null
            && "0".equals(e.getResponseHeaders().getFirst("X-RateLimit-Remaining"));
        if (code == HttpStatus.TOO_MANY_REQUESTS.value() || limitExhausted) {
            return result(CheckState.ERROR, null, null, "GitHub rate limit reached");
        }
        return result(CheckState.ERROR, null, null, "GitHub returned HTTP " + code);
    }

    private VersionStatusDto result(CheckState state, SemVer latest, String url, String message) {
        boolean update = state == CheckState.OK && latest != null && current.isPresent() && latest.compareTo(current.get()) > 0;
        return new VersionStatusDto(
            currentVersion,
            latest == null ? null : latest.toString(),
            url,
            update,
            state,
            state == CheckState.NOT_CHECKED ? null : clock.instant(),
            message);
    }

    private static boolean changed(VersionStatusDto previous, VersionStatusDto next) {
        return previous.checkState() != next.checkState()
            || !Objects.equals(previous.latestVersion(), next.latestVersion())
            || !Objects.equals(previous.message(), next.message());
    }

    private static void logChange(VersionStatusDto previous, VersionStatusDto next) {
        if (!changed(previous, next)) {
            LOGGER.debug("Update check unchanged: {}", next.checkState());
        } else if (next.checkState() == CheckState.ERROR) {
            LOGGER.warn("Update check failed: {}", next.message());
        } else if (next.updateAvailable()) {
            LOGGER.info("Update available: {} -> {} ({})", next.currentVersion(), next.latestVersion(), next.releaseUrl());
        } else {
            LOGGER.info("Update check: {}, running {}", next.checkState(), next.currentVersion());
        }
    }

    private static String versionOf(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? null : build.getVersion();
    }
}
