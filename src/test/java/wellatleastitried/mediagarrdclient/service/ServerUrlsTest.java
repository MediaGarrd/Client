package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServerUrlsTest {

    @ParameterizedTest
    @CsvSource({
        "http://server:38471, http://server:38471",
        "HTTP://Server:38471/, http://server:38471",
        "http://server:80, http://server",
        "https://Garrd.Example.com:443/, https://garrd.example.com",
        "https://garrd.example.com:8443, https://garrd.example.com:8443",
        "https://garrd.example.com///, https://garrd.example.com",
        "https://example.com/garrd/, https://example.com/garrd",
        "http://[::1]:8080, http://[::1]:8080",
        "http://user:pw@server:8080, http://server:8080"
    })
    void normalizesUrls(String input, String expected) {
        assertEquals(expected, ServerUrls.normalize(input));
    }

    @Test
    void leavesNonUrlsAloneInsteadOfFailing() {
        assertEquals("not a url", ServerUrls.normalize(" not a url/ "));
        assertEquals("", ServerUrls.normalize(" "));
        assertNull(ServerUrls.normalize(null));
    }
}
