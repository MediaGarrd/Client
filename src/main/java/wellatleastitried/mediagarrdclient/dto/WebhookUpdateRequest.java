package wellatleastitried.mediagarrdclient.dto;

public record WebhookUpdateRequest(String url) {

    @Override
    public String toString() {
        return "WebhookUpdateRequest[url=<redacted>]";
    }
}
