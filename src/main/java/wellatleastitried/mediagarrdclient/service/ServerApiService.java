package wellatleastitried.mediagarrdclient.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import wellatleastitried.mediagarrdclient.MediaGarrdProperties;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.PairingCodeDto;
import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;
import wellatleastitried.mediagarrdclient.dto.VersionStatusDto;
import wellatleastitried.mediagarrdclient.dto.WebhookStatusDto;
import wellatleastitried.mediagarrdclient.dto.WebhookTestDto;
import wellatleastitried.mediagarrdclient.protocol.Endpoints;

@Service
public class ServerApiService {

    @FunctionalInterface
    public interface DownloadProgressListener {
        void onProgress(long bytesWritten, long totalBytes);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerApiService.class);
    private static final JacksonJsonHttpMessageConverter JSON = new JacksonJsonHttpMessageConverter(
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES));
    private static final String SERVICE_PATH = Endpoints.SERVER_SERVICES.getPath() + "/{name}";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final Duration MIN_BACKUP_RUN_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DOWNLOAD_STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final int BUFFER_SIZE = 64 * 1024;

    private record ServerError(String error) {
    }

    private record PairReply(String token, String id, String name) {

        @Override
        public String toString() {
            return "PairReply[id=" + id + ", name=" + name + "]";
        }
    }

    private record ConfirmReply(boolean healthy, Boolean running, Integer backupRetentionCount, Duration backupInterval) {
    }

    private final ServerTokens tokens;
    private final HttpClient httpClient;
    private final RestClient healthClient;
    private final RestClient restClient;
    private final RestClient backupRunClient;
    private final Duration backupRunTimeout;
    private final Duration stallTimeout;
    private final ScheduledExecutorService stallWatchdog = Executors.newSingleThreadScheduledExecutor(
        runnable -> Thread.ofPlatform().daemon().name("download-stall-watchdog").unstarted(runnable));
    private final List<Consumer<String>> tokenRejectedListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public ServerApiService(MediaGarrdProperties properties, ClientStateService stateService, ServerTls tls) {
        this(checked(properties.getBackupRunTimeout()), stateService, tls);
    }

    private static Duration checked(Duration backupRunTimeout) {
        if (backupRunTimeout == null || backupRunTimeout.compareTo(MIN_BACKUP_RUN_TIMEOUT) < 0) {
            throw new IllegalStateException("mediagarrd.client.backup-run-timeout must be at least " + MIN_BACKUP_RUN_TIMEOUT + ", was " + backupRunTimeout);
        }
        return backupRunTimeout;
    }

    ServerApiService(Duration backupRunTimeout, ServerTokens tokens, ServerTls tls) {
        this(backupRunTimeout, DOWNLOAD_STALL_TIMEOUT, tokens, tls);
    }

    ServerApiService(Duration backupRunTimeout, Duration stallTimeout, ServerTokens tokens, ServerTls tls) {
        this(backupRunTimeout, stallTimeout, HEALTH_TIMEOUT, REQUEST_TIMEOUT, tokens, tls);
    }

    ServerApiService(Duration backupRunTimeout, Duration stallTimeout, Duration healthTimeout, Duration requestTimeout, ServerTokens tokens, ServerTls tls) {
        this.backupRunTimeout = backupRunTimeout;
        this.stallTimeout = stallTimeout;
        this.tokens = tokens;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(tls.context())
            .build();
        this.healthClient = restClient(healthTimeout);
        this.restClient = restClient(requestTimeout);
        this.backupRunClient = restClient(backupRunTimeout);
    }

    private RestClient restClient(Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        if (readTimeout != null) {
            requestFactory.setReadTimeout(readTimeout);
        }
        return RestClient.builder().requestFactory(requestFactory).configureMessageConverters(converters -> converters.withJsonConverter(JSON)).build();
    }

    public ServerStatusDto health(String serverUrl) {
        RestClient.RequestHeadersSpec<?> request = healthClient.get().uri(serverUrl + Endpoints.SERVER_HEALTH.getPath());
        Optional<String> token = tokens.tokenFor(serverUrl);
        if (token.isPresent()) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get());
        }
        ServerStatusDto status = checked(request.retrieve()).body(ServerStatusDto.class);
        LOGGER.debug("Server health of {}: {}", serverUrl, status);
        return status;
    }

    public VersionStatusDto serverVersion(String serverUrl) {
        return checked(healthClient.get()
            .uri(serverUrl + Endpoints.SERVER_VERSION.getPath())
            .retrieve())
            .body(VersionStatusDto.class);
    }

    public List<BackupDto> listBackups(String serverUrl) {
        List<BackupDto> backups = authed(restClient.get().uri(serverUrl + Endpoints.SERVER_BACKUPS.getPath()), serverUrl)
            .body(new ParameterizedTypeReference<>() {});
        LOGGER.debug("Received {} backup(s) from {}", backups == null ? 0 : backups.size(), serverUrl);
        return backups == null ? List.of() : backups;
    }

    public void triggerBackup(String serverUrl) {
        LOGGER.info("Triggering backup on {}", serverUrl);
        try {
            authed(backupRunClient.post().uri(serverUrl + Endpoints.SERVER_BACKUP_RUN.getPath()), serverUrl)
                .toBodilessEntity();
        } catch (ResourceAccessException e) {
            if (RequestTimeouts.isReadTimeout(e)) {
                LOGGER.warn("Backup on {} did not finish within {}", serverUrl, backupRunTimeout);
                throw new BackupRunTimeoutException("The backup did not finish within " + backupRunTimeout
                    + " and is probably still running on the server. The dashboard's live status shows when it finishes.", e);
            }
            throw e;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new BackupRunFailedException(failureReason(e), e);
            }
            throw e;
        }
        LOGGER.info("Backup finished on {}", serverUrl);
    }

    private static String failureReason(RestClientResponseException e) {
        try {
            ServerError body = e.getResponseBodyAs(ServerError.class);
            if (body != null && body.error() != null && !body.error().isBlank()) {
                return body.error();
            }
        } catch (RuntimeException ignored) {
        }
        return "MediaGarrd server responded with " + e.getStatusCode().value();
    }

    public ServerStatusDto updateServerSchedule(String serverUrl, Duration interval) {
        LOGGER.info("Updating server schedule on {} to {}", serverUrl, interval);
        return authed(restClient.put()
            .uri(serverUrl + Endpoints.SERVER_SCHEDULE.getPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("backupInterval", interval.toString())), serverUrl)
            .body(ServerStatusDto.class);
    }

    public ServerConfigDto serverConfig(String serverUrl) {
        return authed(restClient.get().uri(serverUrl + Endpoints.SERVER_CONFIG.getPath()), serverUrl)
            .body(ServerConfigDto.class);
    }

    public ServerConfigDto updateServerConfig(String serverUrl, String backupLocation, Integer retentionCount) {
        LOGGER.info("Updating server configuration on {}: backupLocation={}, retentionCount={}", serverUrl, backupLocation, retentionCount);
        Map<String, Object> body = new HashMap<>();
        body.put("backupLocation", backupLocation);
        body.put("retentionCount", retentionCount);
        return authed(restClient.put()
            .uri(serverUrl + Endpoints.SERVER_CONFIG.getPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body), serverUrl)
            .body(ServerConfigDto.class);
    }

    public WebhookStatusDto webhook(String serverUrl) {
        return authed(restClient.get().uri(serverUrl + Endpoints.SERVER_WEBHOOK.getPath()), serverUrl)
            .body(WebhookStatusDto.class);
    }

    public WebhookStatusDto updateWebhook(String serverUrl, String url) {
        LOGGER.info("{} the failure webhook on {}", url == null || url.isBlank() ? "Clearing" : "Updating", serverUrl);
        Map<String, Object> body = new HashMap<>();
        body.put("url", url);
        return authed(restClient.put()
            .uri(serverUrl + Endpoints.SERVER_WEBHOOK.getPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body), serverUrl)
            .body(WebhookStatusDto.class);
    }

    public WebhookTestDto testWebhook(String serverUrl) {
        LOGGER.info("Sending a test message through the failure webhook on {}", serverUrl);
        return authed(restClient.post().uri(serverUrl + Endpoints.SERVER_WEBHOOK_TEST.getPath()), serverUrl)
            .body(WebhookTestDto.class);
    }

    public List<ServiceDto> listServices(String serverUrl) {
        List<ServiceDto> services = authed(restClient.get().uri(serverUrl + Endpoints.SERVER_SERVICES.getPath()), serverUrl)
            .body(new ParameterizedTypeReference<>() {});
        return services == null ? List.of() : services;
    }

    public ServiceDto addService(String serverUrl, ServiceDto service) {
        LOGGER.info("Adding service {} on {}", service.name(), serverUrl);
        return authed(restClient.post()
            .uri(serverUrl + Endpoints.SERVER_SERVICES.getPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(service), serverUrl)
            .body(ServiceDto.class);
    }

    public ServiceDto updateService(String serverUrl, String name, ServiceDto service) {
        LOGGER.info("Updating service {} on {}", name, serverUrl);
        return authed(restClient.put()
            .uri(serverUrl + SERVICE_PATH, name)
            .contentType(MediaType.APPLICATION_JSON)
            .body(service), serverUrl)
            .body(ServiceDto.class);
    }

    public void removeService(String serverUrl, String name) {
        LOGGER.info("Removing service {} from {}", name, serverUrl);
        authed(restClient.delete().uri(serverUrl + SERVICE_PATH, name), serverUrl)
            .toBodilessEntity();
    }

    public StoredPairing pair(String serverUrl, String credential, String name) {
        LOGGER.info("Pairing with {}", serverUrl);
        Map<String, String> body = new HashMap<>();
        body.put("pin", credential);
        if (name != null && !name.isBlank()) {
            body.put("name", name);
        }
        PairReply reply;
        try {
            reply = redirectsRefused(restClient.post()
                .uri(serverUrl + Endpoints.SERVER_PAIR.getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve())
                .body(PairReply.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new InvalidRequestException(serverMessage(e, "The PIN or pairing code was not accepted"));
            }
            throw e;
        }
        if (reply == null || reply.token() == null || reply.token().isBlank()) {
            throw new UpstreamResponseException("The server did not return a token");
        }
        return new StoredPairing(reply.token(), reply.id(), reply.name());
    }

    public void confirmPairing(String serverUrl, String token) {
        LOGGER.info("Confirming the pairing with {}", serverUrl);
        ConfirmReply reply;
        try {
            reply = redirectsRefused(healthClient.get()
                .uri(serverUrl + Endpoints.SERVER_HEALTH.getPath())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve())
                .body(ConfirmReply.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new InvalidRequestException("The server did not accept this pairing. Enter the PIN or a pairing code again");
            }
            int status = e.getStatusCode().value();
            String message = "The server could not confirm the pairing (status " + status + "). Try again";
            if (status == 502 || status == 503 || status == 504) {
                throw new ConfirmationUnavailableException(message);
            }
            throw new UpstreamResponseException(message);
        }
        if (reply == null || reply.running() == null || reply.backupRetentionCount() == null) {
            throw new ConfirmationRefusedException("The server did not confirm the pairing. Check that it is a current MediaGarrd Server and try again");
        }
    }

    public PairingCodeDto createPairingCode(String serverUrl) {
        return authed(restClient.post().uri(serverUrl + Endpoints.SERVER_PAIRING_CODES.getPath()), serverUrl)
            .body(PairingCodeDto.class);
    }

    public void revokeClient(String serverUrl, String clientId) {
        LOGGER.info("Unpairing from {}", serverUrl);
        authed(restClient.delete().uri(serverUrl + Endpoints.SERVER_CLIENTS.getPath() + "/{id}", clientId), serverUrl)
            .toBodilessEntity();
    }

    public void addTokenRejectedListener(Consumer<String> listener) {
        tokenRejectedListeners.add(listener);
    }

    private RestClient.ResponseSpec authed(RestClient.RequestHeadersSpec<?> request, String serverUrl) {
        String token = requireToken(serverUrl);
        return checked(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve(), serverUrl);
    }

    private RestClient.ResponseSpec checked(RestClient.ResponseSpec response, String serverUrl) {
        return redirectsRefused(response).onStatus(status -> status.value() == 401, (request, reply) -> {
            throw rejected(serverUrl);
        });
    }

    private PairingRequiredException rejected(String serverUrl) {
        for (Consumer<String> listener : tokenRejectedListeners) {
            try {
                listener.accept(serverUrl);
            } catch (RuntimeException e) {
                LOGGER.warn("Token rejected listener failed: {}", e.getMessage());
            }
        }
        return rejected();
    }

    private static RestClient.ResponseSpec checked(RestClient.ResponseSpec response) {
        return redirectsRefused(response).onStatus(status -> status.value() == 401, (request, reply) -> {
            throw rejected();
        });
    }

    private static RestClient.ResponseSpec redirectsRefused(RestClient.ResponseSpec response) {
        return response.onStatus(status -> status.is3xxRedirection(), (request, reply) -> {
            throw new ServerRedirectException(reply.getStatusCode().value(), reply.getHeaders().getFirst(HttpHeaders.LOCATION));
        });
    }

    private String requireToken(String serverUrl) {
        return tokens.tokenFor(serverUrl)
            .orElseThrow(() -> new PairingRequiredException("This client is not paired with the server, pair it first"));
    }

    private static PairingRequiredException rejected() {
        return new PairingRequiredException("The server rejected this client's token, pair it again");
    }

    private static String serverMessage(RestClientResponseException e, String fallback) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            Object message = body == null ? null : body.get("error");
            return message == null ? fallback : message.toString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public Path downloadById(String serverUrl, String backupId, Path destination, DownloadProgressListener progressListener) {
        URI uri = URI.create(serverUrl + Endpoints.SERVER_BACKUPS.getPath() + "/" + UriUtils.encodePathSegment(backupId, StandardCharsets.UTF_8) + "/download");
        LOGGER.info("Downloading backup {} from {} to {}", backupId, serverUrl, destination);
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");
        String token = requireToken(serverUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .GET()
                .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 401) {
                    throw rejected(serverUrl);
                }
                if (response.statusCode() / 100 == 3) {
                    throw new ServerRedirectException(response.statusCode(), response.headers().firstValue(HttpHeaders.LOCATION).orElse(null));
                }
                if (response.statusCode() / 100 != 2) {
                    String snippet = new String(body.readNBytes(256), StandardCharsets.UTF_8);
                    LOGGER.error("Download of backup {} failed with HTTP {}: {}", backupId, response.statusCode(), snippet);
                    throw new DownloadFailedException("Download of backup " + backupId + " failed with HTTP " + response.statusCode());
                }
                OwnerOnlyFiles.createDirectories(destination.getParent());
                long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                copy(body, temp, totalBytes, progressListener);
            }
            if (!looksLikeZip(temp)) {
                throw new DownloadFailedException("Server returned non-zip content for backup " + backupId);
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("Downloaded backup {} to {} ({} bytes)", backupId, destination, Files.size(destination));
            return destination;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadFailedException("Download of backup " + backupId + " was interrupted", e);
        } catch (IOException e) {
            LOGGER.error("Failed to save backup {} to {}", backupId, destination, e);
            throw new DownloadFailedException("Failed to download backup " + backupId, e);
        } finally {
            QuietFiles.delete(temp);
        }
    }

    private void copy(InputStream in, Path target, long totalBytes, DownloadProgressListener progressListener) throws IOException {
        AtomicLong lastRead = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        long checkMillis = Math.max(10, stallTimeout.toMillis() / 4);
        ScheduledFuture<?> guard = stallWatchdog.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - lastRead.get() > stallTimeout.toNanos() && stalled.compareAndSet(false, true)) {
                try {
                    in.close();
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
        try (OutputStream out = OwnerOnlyFiles.newOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long written = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                lastRead.set(System.nanoTime());
                out.write(buffer, 0, read);
                written += read;
                if (progressListener != null) {
                    progressListener.onProgress(written, totalBytes);
                }
            }
        } catch (IOException e) {
            if (stalled.get()) {
                throw new DownloadFailedException("The server stopped sending data for " + stallTimeout.toSeconds() + " seconds", e);
            }
            throw e;
        } finally {
            guard.cancel(false);
        }
    }

    private static boolean looksLikeZip(Path path) throws IOException {
        byte[] header = new byte[4];
        try (InputStream in = Files.newInputStream(path)) {
            if (in.readNBytes(header, 0, 4) < 4) {
                return false;
            }
        }
        return header[0] == 'P' && header[1] == 'K'
            && ((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6) || (header[2] == 7 && header[3] == 8));
    }

    @PreDestroy
    void shutdown() {
        stallWatchdog.shutdownNow();
    }
}
