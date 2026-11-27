package wellatleastitried.mediagarrdclient.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CsrfCheckTest {

    private static MockHttpServletRequest post() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/client/backups/run");
        request.addHeader("Host", "192.168.1.5:8081");
        return request;
    }

    @Test
    void safeMethodsAreAlwaysAllowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/client/status");
        request.addHeader("Origin", "http://evil.example.com");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        assertTrue(CsrfCheck.allows(request));
    }

    @Test
    void aSameOriginFetchMetadataHeaderIsAccepted() {
        MockHttpServletRequest request = post();
        request.addHeader("Sec-Fetch-Site", "same-origin");

        assertTrue(CsrfCheck.allows(request));
    }

    @Test
    void anyOtherFetchMetadataValueIsRefusedEvenWithAMatchingOrigin() {
        for (String site : new String[] {"same-site", "cross-site", "none"}) {
            MockHttpServletRequest request = post();
            request.addHeader("Sec-Fetch-Site", site);
            request.addHeader("Origin", "http://192.168.1.5:8081");

            assertFalse(CsrfCheck.allows(request), site);
        }
    }

    @Test
    void anOriginMatchingTheHostIsAcceptedWithoutFetchMetadata() {
        MockHttpServletRequest request = post();
        request.addHeader("Origin", "http://192.168.1.5:8081");

        assertTrue(CsrfCheck.allows(request));
    }

    @Test
    void anotherPortOnTheSameHostIsAnotherOrigin() {
        MockHttpServletRequest request = post();
        request.addHeader("Origin", "http://192.168.1.5:8096");

        assertFalse(CsrfCheck.allows(request));
    }

    @Test
    void aForeignOriginAndTheNullOriginAreRefused() {
        MockHttpServletRequest foreign = post();
        foreign.addHeader("Origin", "https://evil.example.com");
        MockHttpServletRequest opaque = post();
        opaque.addHeader("Origin", "null");

        assertFalse(CsrfCheck.allows(foreign));
        assertFalse(CsrfCheck.allows(opaque));
    }

    @Test
    void aProxyThatKeepsTheOriginalHostInAForwardedHeaderIsSupported() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/client/server");
        request.addHeader("Host", "client:8081");
        request.addHeader("X-Forwarded-Host", "garrd.example.com");
        request.addHeader("Origin", "https://garrd.example.com");

        assertTrue(CsrfCheck.allows(request));
    }

    @Test
    void requestsWithoutBrowserHeadersNeedTheCustomHeader() {
        MockHttpServletRequest without = post();
        MockHttpServletRequest with = post();
        with.addHeader("X-MediaGarrd-Request", "1");
        MockHttpServletRequest wrong = post();
        wrong.addHeader("X-MediaGarrd-Request", "0");

        assertFalse(CsrfCheck.allows(without));
        assertTrue(CsrfCheck.allows(with));
        assertFalse(CsrfCheck.allows(wrong));
    }
}
