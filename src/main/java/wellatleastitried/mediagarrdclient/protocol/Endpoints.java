package wellatleastitried.mediagarrdclient.protocol;

public enum Endpoints {

    SERVER_HEALTH("/api/v1/health"),
    SERVER_VERSION("/api/v1/version"),
    SERVER_BACKUPS("/api/v1/backups"),
    SERVER_BACKUP_RUN("/api/v1/backups/run"),
    SERVER_SCHEDULE("/api/v1/schedule"),
    SERVER_CONFIG("/api/v1/config"),
    SERVER_SERVICES("/api/v1/services"),
    SERVER_WEBHOOK("/api/v1/notifications/webhook"),
    SERVER_WEBHOOK_TEST("/api/v1/notifications/webhook/test"),
    SERVER_EVENTS("/api/v1/events"),
    SERVER_PAIR("/api/v1/pair"),
    SERVER_PAIRING_CODES("/api/v1/pairing-codes"),
    SERVER_CLIENTS("/api/v1/clients");

    private final String path;

    Endpoints(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
