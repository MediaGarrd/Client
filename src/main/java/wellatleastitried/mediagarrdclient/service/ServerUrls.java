package wellatleastitried.mediagarrdclient.service;

import java.net.URI;
import java.util.Locale;

public final class ServerUrls {

    private ServerUrls() {
    }

    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return stripTrailingSlashes(trimmed);
            }
            int port = uri.getPort();
            boolean defaultPort = port < 0 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            String path = uri.getRawPath() == null ? "" : stripTrailingSlashes(uri.getRawPath());
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path;
        } catch (IllegalArgumentException e) {
            return stripTrailingSlashes(trimmed);
        }
    }

    public static String requireHttp(String serverUrl) {
        String trimmed = stripTrailingSlashes(serverUrl.trim());
        try {
            URI uri = URI.create(trimmed);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null && !uri.getHost().isBlank()) {
                return normalize(trimmed);
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new InvalidRequestException("serverUrl must be an http:// or https:// URL: " + serverUrl);
    }

    public static String nullIfBlank(String url) {
        return url == null || url.isBlank() ? null : url;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
