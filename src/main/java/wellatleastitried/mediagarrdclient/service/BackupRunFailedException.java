package wellatleastitried.mediagarrdclient.service;

public class BackupRunFailedException extends RuntimeException {

    public BackupRunFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
