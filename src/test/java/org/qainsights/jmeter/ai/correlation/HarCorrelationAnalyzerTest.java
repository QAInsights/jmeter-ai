package org.qainsights.jmeter.ai.correlation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HarCorrelationAnalyzerTest {

    // Response #0 returns a csrf token; request #1 sends it back -> a correlation.
    private static final String HAR = "{\"log\":{\"entries\":["
            + "{\"request\":{\"method\":\"GET\",\"url\":\"https://app.example.com/login\",\"headers\":[]},"
            + " \"response\":{\"content\":{\"text\":\"{\\\"csrfToken\\\":\\\"abc123DEF456ghi789\\\"}\"}}},"
            + "{\"request\":{\"method\":\"POST\",\"url\":\"https://app.example.com/submit\","
            + "  \"headers\":[{\"name\":\"X-CSRF\",\"value\":\"abc123DEF456ghi789\"}],"
            + "  \"postData\":{\"text\":\"data=1\"}},\"response\":{\"content\":{\"text\":\"{}\"}}}"
            + "]}}";

    @Test
    void detectsDynamicTokenReusedInLaterRequest() throws Exception {
        List<CorrelationCandidate> candidates = HarCorrelationAnalyzer.analyze(HAR);
        assertEquals(1, candidates.size());
        CorrelationCandidate c = candidates.get(0);
        assertEquals("csrfToken", c.getParameterName());
        assertEquals("abc123DEF456ghi789", c.getSampleValue());
        assertEquals(0, c.getSourceSamplerIndex());
        assertEquals("json", c.getExtractorType());
        assertEquals("$..csrfToken", c.getExtractionPattern());
        assertEquals("csrfToken", c.getVariableName());
        assertEquals(1, c.getUsageCount());
    }

    @Test
    void ignoresTokensNeverReused() throws Exception {
        String har = "{\"log\":{\"entries\":["
                + "{\"request\":{\"method\":\"GET\",\"url\":\"https://h/a\",\"headers\":[]},"
                + " \"response\":{\"content\":{\"text\":\"{\\\"sessionId\\\":\\\"zzz999yyy888www\\\"}\"}}},"
                + "{\"request\":{\"method\":\"GET\",\"url\":\"https://h/b\",\"headers\":[]},"
                + " \"response\":{\"content\":{\"text\":\"{}\"}}}"
                + "]}}";
        assertTrue(HarCorrelationAnalyzer.analyze(har).isEmpty(),
                "a token that is never sent back is not a correlation");
    }

    @Test
    void isDynamic_keyVsValueHeuristics() {
        assertTrue(HarCorrelationAnalyzer.isDynamic("csrf_token", "shortish1"));
        assertTrue(HarCorrelationAnalyzer.isDynamic("anything",
                "550e8400-e29b-41d4-a716-446655440000")); // UUID by value
        assertFalse(HarCorrelationAnalyzer.isDynamic("name", "Alice"));   // too short / plain
        assertFalse(HarCorrelationAnalyzer.isDynamic("description",
                "this is a fairly long plain sentence")); // long but no digits
    }

    @Test
    void sanitizeVar_makesValidVariableName() {
        assertEquals("csrf_token", HarCorrelationAnalyzer.sanitizeVar("csrf-token"));
        assertEquals("_1id", HarCorrelationAnalyzer.sanitizeVar("1id"));
    }
}
