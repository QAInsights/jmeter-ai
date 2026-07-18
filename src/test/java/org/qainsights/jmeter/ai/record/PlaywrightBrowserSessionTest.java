package org.qainsights.jmeter.ai.record;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PlaywrightBrowserSession} helpers (origins and secret resolution).
 */
class PlaywrightBrowserSessionTest {

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @Test
    void should_allowOrigin_when_matchingBaseUri() {
        String url = "https://example.com/login";
        String baseUri = "https://example.com";
        assertTrue(PlaywrightBrowserSession.isAllowedOrigin(url, baseUri, ""));
    }

    @Test
    void should_allowOrigin_when_inAllowedOriginsList() {
        String url = "https://sub.example.com/api";
        String baseUri = "https://example.com";
        assertTrue(PlaywrightBrowserSession.isAllowedOrigin(url, baseUri, "sub.example.com, other.org"));
    }

    @Test
    void should_rejectOrigin_when_notMatchingAny() {
        String url = "https://malicious.com/hack";
        String baseUri = "https://example.com";
        assertFalse(PlaywrightBrowserSession.isAllowedOrigin(url, baseUri, "sub.example.com"));
    }

    @Test
    void should_resolveSecret_when_propertyExists() {
        JMeterUtils.setProperty("MY_SECRET", "super-secret");
        String resolved = PlaywrightBrowserSession.resolveSecret("${MY_SECRET}");
        assertEquals("super-secret", resolved);
    }

    @Test
    void should_throwException_when_secretNotResolved() {
        assertThrows(RecordingException.class, () -> PlaywrightBrowserSession.resolveSecret("${MISSING_VAL}"));
    }

    @Test
    void should_resolveEmbeddedSecrets() {
        JMeterUtils.setProperty("PART1", "abc");
        JMeterUtils.setProperty("PART2", "xyz");
        String resolved = PlaywrightBrowserSession.resolveSecret("prefix-${PART1}-middle-${PART2}-suffix");
        assertEquals("prefix-abc-middle-xyz-suffix", resolved);
    }
}
