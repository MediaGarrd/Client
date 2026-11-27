package wellatleastitried.mediagarrdclient.service;

public class DownloadFailedException extends RuntimeException {

    public DownloadFailedException(String message) {
        super(message);
    }

    public DownloadFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
