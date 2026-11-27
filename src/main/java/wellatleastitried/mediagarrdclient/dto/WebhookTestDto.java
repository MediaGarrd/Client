package wellatleastitried.mediagarrdclient.dto;

public record WebhookTestDto(boolean delivered, Integer status, String error) {
}
