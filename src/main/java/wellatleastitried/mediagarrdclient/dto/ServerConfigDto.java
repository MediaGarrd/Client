package wellatleastitried.mediagarrdclient.dto;

import java.time.Duration;

public record ServerConfigDto(String backupLocation, Integer retentionCount, Duration backupInterval) {
}
