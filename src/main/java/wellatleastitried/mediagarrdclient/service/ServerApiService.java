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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import wellatleastitried.mediagarrdclient.api.Endpoints;
import wellatleastitried.mediagarrdclient.dto.BackupDto;
import wellatleastitried.mediagarrdclient.dto.ServerConfigDto;
import wellatleastitried.mediagarrdclient.dto.ServerStatusDto;
import wellatleastitried.mediagarrdclient.dto.ServiceDto;

@Service
public class ServerApiService {

    @FunctionalInterface
    public interface DownloadProgressListener {
        void onProgress(long bytesWritten, long totalBytes);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerApiService.class);
    private static final String SERVICE_PATH = Endpoints.SERVER_SERVICES.getPath() + "/{name}";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final RestClient healthClient = restClient(HEALTH_TIMEOUT);
    private final RestClient restClient = restClient(REQUEST_TIMEOUT);
    private final RestClient backupRunClient = restClient(null);

    private RestClient restClient(Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        if (readTimeout != null) {
            requestFactory.setReadTimeout(readTimeout);
        }
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    public ServerStatusDto health(String serverUrl) {
        ServerStatusDto status = healthClient.get()
            .uri(serverUrl + Endpoints.SERVER_HEALTH.getPath())
            .retrieve()
            .body(ServerStatusDto.class);
        LOGGER.debug("Server health of {}: {}", serverUrl, status);
        return status;
    }

    public List<BackupDto> listBackups(String serverUrl) {
        List<BackupDto> backups = restClient.get()
            .uri(serverUrl + Endpoints.SERVER_BACKUPS.getPath())
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
        LOGGER.debug("Received {} backup(s) from {}", backups == null ? 0 : backups.size(), serverUrl);
        return backups == null ? List.of() : backups;
    }

    public void triggerBackup(String serverUrl) {
        LOGGER.info("Triggering backup on {}", serverUrl);
        backupRunClient.post()
            .uri(serverUrl + Endpoints.SERVER_BACKUP_RUN.getPath())
            .retrieve()
            .toBodilessEntity();
        LOGGER.info("Backup finished on {}", serverUrl);
    }

    public ServerStatusDto updateServerSchedule(String serverUrl, Duration interval) {
        LOGGER.info("Updating server schedule on {} to {}", serverUrl, interval);
        return restClient.put()
            .uri(serverUrl + Endpoints.SERVER_SCHEDULE.getPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("backupInterval", interval.toString()))
            .retrieve()
            .body(ServerStatusDto.class);
    }

    public ServerConfigDto serverConfig(String serverUrl) {
        return restClient.get()
            .uri(serverUrl + Endpoints.SERVER_CONFIG.getPath())
            .retrieve()
            .body(ServerConfigDto.class);
    }

    public ServerConfigDto updateServerConfig(String serverUrl, String backupLocation, Integer retentionCount) {
        LOGGER.info("Updating server configuration on {}: backupLocation={}, retentionCount={}", serverUrl, backupLocation, retentionCount);
        Map<String, Object> body = new HashMap<>();
        body.put("backupLocation", backupLocation);
        body.put("retentionCount", retentionCount);
        return restClient.put()
            .uri(serverUrl + Endpoints.SERVER_CONFIG.getPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(ServerConfigDto.class);
    }

    public List<ServiceDto> listServices(String serverUrl) {
        List<ServiceDto> services = restClient.get()
            .uri(serverUrl + Endpoints.SERVER_SERVICES.getPath())
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
        return services == null ? List.of() : services;
    }

    public ServiceDto addService(String serverUrl, ServiceDto service) {
        LOGGER.info("Adding service {} on {}", service.name(), serverUrl);
        return restClient.post()
            .uri(serverUrl + Endpoints.SERVER_SERVICES.getPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(service)
            .retrieve()
            .body(ServiceDto.class);
    }

    public ServiceDto updateService(String serverUrl, String name, ServiceDto service) {
        LOGGER.info("Updating service {} on {}", name, serverUrl);
        return restClient.put()
            .uri(serverUrl + SERVICE_PATH, name)
            .contentType(MediaType.APPLICATION_JSON)
            .body(service)
            .retrieve()
            .body(ServiceDto.class);
    }

    public void removeService(String serverUrl, String name) {
        LOGGER.info("Removing service {} from {}", name, serverUrl);
        restClient.delete()
            .uri(serverUrl + SERVICE_PATH, name)
            .retrieve()
            .toBodilessEntity();
    }

    public Path downloadById(String serverUrl, String backupId, Path destination, DownloadProgressListener progressListener) {
        URI uri = URI.create(serverUrl + Endpoints.SERVER_BACKUPS.getPath() + "/" + UriUtils.encodePathSegment(backupId, StandardCharsets.UTF_8) + "/download");
        LOGGER.info("Downloading backup {} from {} to {}", backupId, serverUrl, destination);
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            HttpResponse<InputStream> response = httpClient.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    String snippet = new String(body.readNBytes(256), StandardCharsets.UTF_8);
                    LOGGER.error("Download of backup {} failed with HTTP {}: {}", backupId, response.statusCode(), snippet);
                    throw new IllegalStateException("Download of backup " + backupId + " failed with HTTP " + response.statusCode());
                }
                Files.createDirectories(destination.getParent());
                long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                copy(body, temp, totalBytes, progressListener);
            }
            if (!looksLikeZip(temp)) {
                throw new IllegalStateException("Server returned non-zip content for backup " + backupId);
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("Downloaded backup {} to {} ({} bytes)", backupId, destination, Files.size(destination));
            return destination;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download of backup " + backupId + " was interrupted", e);
        } catch (IOException e) {
            LOGGER.error("Failed to save backup {} to {}", backupId, destination, e);
            throw new IllegalStateException("Failed to save backup " + backupId + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void copy(InputStream in, Path target, long totalBytes, DownloadProgressListener progressListener) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long written = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                written += read;
                if (progressListener != null) {
                    progressListener.onProgress(written, totalBytes);
                }
            }
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

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }
}
