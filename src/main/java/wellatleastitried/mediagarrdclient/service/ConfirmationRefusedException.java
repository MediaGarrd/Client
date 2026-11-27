package wellatleastitried.mediagarrdclient.service;

public class ConfirmationRefusedException extends UpstreamResponseException {

    public ConfirmationRefusedException(String message) {
        super(message);
    }
}
