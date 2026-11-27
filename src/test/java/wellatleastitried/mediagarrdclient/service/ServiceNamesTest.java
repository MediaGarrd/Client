package wellatleastitried.mediagarrdclient.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ServiceNamesTest {

    @Test
    void acceptsTheNamesTheServerAccepts() {
        assertEquals("radarr", ServiceNames.require("radarr"));
        assertEquals("Sonarr_4k.v2-x", ServiceNames.require("Sonarr_4k.v2-x"));
        assertEquals("a".repeat(64), ServiceNames.require("a".repeat(64)));
    }

    @Test
    void refusesNamesThatCouldChangeThePath() {
        for (String name : new String[] {null, "", ".", "..", ".hidden", "a/b", "a\\b", "a b", "%2e%2e", "a".repeat(65), "x?y", "x#y"}) {
            assertThrows(InvalidRequestException.class, () -> ServiceNames.require(name), String.valueOf(name));
        }
    }
}
