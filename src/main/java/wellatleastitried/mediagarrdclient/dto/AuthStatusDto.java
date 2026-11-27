package wellatleastitried.mediagarrdclient.dto;

public record AuthStatusDto(boolean passwordSet, boolean passwordFromEnvironment, boolean authenticated) {
}
