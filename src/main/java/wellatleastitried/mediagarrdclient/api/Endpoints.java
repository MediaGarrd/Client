package wellatleastitried.mediagarrdclient.api;

public enum Endpoints {

    SERVER_HEALTH("/api/v1/health"),
    SERVER_BACKUPS("/api/v1/backups"),
    SERVER_BACKUP_RUN("/api/v1/backups/run"),
    SERVER_SCHEDULE("/api/v1/schedule"),
    SERVER_CONFIG("/api/v1/config"),
    SERVER_SERVICES("/api/v1/services");

    private final String path;

    Endpoints(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
