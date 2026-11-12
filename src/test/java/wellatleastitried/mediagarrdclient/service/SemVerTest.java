package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemVerTest {

    private static SemVer v(String text) {
        return SemVer.parse(text).orElseThrow();
    }

    @Test
    void parsesWithAndWithoutLeadingV() {
        assertEquals(new SemVer(0, 1, 0, List.of()), v("0.1.0"));
        assertEquals(new SemVer(0, 1, 0, List.of()), v("v0.1.0"));
        assertEquals(new SemVer(12, 34, 56, List.of()), v(" v12.34.56 "));
    }

    @Test
    void parsesPrereleaseAndIgnoresBuildMetadata() {
        assertEquals(new SemVer(1, 0, 0, List.of("rc", "1")), v("v1.0.0-rc.1+build.5"));
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "1.2", "v1.2", "1.2.3.4", "latest", "1.2.x", "1.2.3-", "1.2.3-a..b", "vv1.2.3", "1.2.3 beta", "99999999999999999999.0.0"})
    void rejectsInvalidVersions(String text) {
        assertEquals(Optional.empty(), SemVer.parse(text));
    }

    @Test
    void rejectsNull() {
        assertEquals(Optional.empty(), SemVer.parse(null));
    }

    @Test
    void comparesNumericallyNotLexically() {
        assertTrue(v("0.10.0").compareTo(v("0.9.0")) > 0);
        assertTrue(v("1.0.0").compareTo(v("0.99.99")) > 0);
        assertTrue(v("0.1.10").compareTo(v("0.1.9")) > 0);
        assertTrue(v("0.1.0").compareTo(v("0.2.0")) < 0);
        assertEquals(0, v("v0.1.0").compareTo(v("0.1.0")));
    }

    @Test
    void ordersPrereleasesPerSemver() {
        List<String> ascending = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0");
        for (int i = 0; i < ascending.size() - 1; i++) {
            assertTrue(v(ascending.get(i)).compareTo(v(ascending.get(i + 1))) < 0, ascending.get(i) + " < " + ascending.get(i + 1));
            assertTrue(v(ascending.get(i + 1)).compareTo(v(ascending.get(i))) > 0);
        }
    }

    @Test
    void rendersWithoutPrefixOrMetadata() {
        assertEquals("0.2.0", v("v0.2.0").toString());
        assertEquals("1.0.0-rc.1", v("v1.0.0-rc.1+x").toString());
    }
}
