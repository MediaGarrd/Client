package wellatleastitried.mediagarrdclient.dto;

import java.util.List;

public record DashboardState(
    String activeServer,
    List<String> knownServers,
    LinkState link,
    Boolean running,
    HealthSummary health,
    VersionsDto versions
) {
}
