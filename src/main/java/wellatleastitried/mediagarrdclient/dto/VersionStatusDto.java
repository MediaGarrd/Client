package wellatleastitried.mediagarrdclient.dto;

import java.time.Instant;

public record VersionStatusDto(
    String currentVersion,
    String latestVersion,
    String releaseUrl,
    boolean updateAvailable,
    CheckState checkState,
    Instant checkedAt,
    String message
) {}
