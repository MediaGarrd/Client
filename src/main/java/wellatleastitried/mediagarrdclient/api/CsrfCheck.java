package wellatleastitried.mediagarrdclient.api;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

final class CsrfCheck {

    static final String HEADER = "X-MediaGarrd-Request";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private CsrfCheck() {
    }

    static boolean allows(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null) {
            return "same-origin".equalsIgnoreCase(fetchSite.trim());
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return "1".equals(request.getHeader(HEADER));
        }
        String authority = originAuthority(origin);
        if (authority == null) {
            return false;
        }
        return authority.equalsIgnoreCase(firstValue(request.getHeader("Host")))
            || authority.equalsIgnoreCase(firstValue(request.getHeader("X-Forwarded-Host")));
    }

    private static String originAuthority(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            return uri.getScheme() == null ? null : uri.getRawAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstValue(String header) {
        if (header == null) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
    }
}
