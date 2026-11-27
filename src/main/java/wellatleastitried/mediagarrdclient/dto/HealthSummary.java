package wellatleastitried.mediagarrdclient.dto;

import java.time.Duration;

public record HealthSummary(Duration backupInterval, int backupRetentionCount) {
}
