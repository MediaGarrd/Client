package wellatleastitried.mediagarrdclient.service;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

public final class RequestTimeouts {

    private static final int MAX_DEPTH = 16;

    private RequestTimeouts() {
    }

    public static boolean isTimeout(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < MAX_DEPTH; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    public static boolean isReadTimeout(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < MAX_DEPTH; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
