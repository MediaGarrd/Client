package wellatleastitried.mediagarrdclient.service;

public class NoBackupsException extends NotFoundException {

    public NoBackupsException() {
        super("No backups found on server");
    }
}
