package wellatleastitried.mediagarrdclient.dto;

public record PasswordRequest(String password) {

    @Override
    public String toString() {
        return "PasswordRequest[]";
    }
}
