package wellatleastitried.mediagarrdclient.service;

public class ServerRedirectException extends RuntimeException {

    private final String location;

    public ServerRedirectException(int status, String location) {
        super("The server answered with a redirect (HTTP " + status + ")"
            + (location == null || location.isBlank() ? "" : " to " + location)
            + ". The client does not follow redirects, so use that address as the server address instead.");
        this.location = location;
    }

    public String location() {
        return location;
    }
}
