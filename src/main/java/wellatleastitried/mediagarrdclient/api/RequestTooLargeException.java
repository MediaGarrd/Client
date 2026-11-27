package wellatleastitried.mediagarrdclient.api;

import java.io.IOException;

public class RequestTooLargeException extends IOException {

    public RequestTooLargeException(long limit) {
        super("The request body is larger than " + limit + " bytes");
    }
}
