package org.qainsights.jmeter.ai.utils;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeTextTest {

    @Test
    void redactsUrlUserInfo() {
        assertEquals("https://***@mirror.example/path",
                SafeText.redactUrls("https://alice:s3cret@mirror.example/path"));
    }

    @Test
    void redactsUrlUserInfoContainingAtSign() {
        assertEquals("https://***@mirror.example/path",
                SafeText.redactUrls("https://alice:p@ss@mirror.example/path"));
    }

    @Test
    void redactsUrlQuery() {
        assertEquals("https://mirror.example/path?***",
                SafeText.redactUrls("https://mirror.example/path?token=abc123"));
    }

    @Test
    void redactsUserInfoAndQueryInTheSameUrl() {
        assertEquals("https://***@mirror.example/path?***",
                SafeText.redactUrls("https://alice:s3cret@mirror.example/path?token=abc123"));
    }

    @Test
    void preservesNull() {
        assertNull(SafeText.redactUrls(null));
    }

    @Test
    void leavesTextWithoutUrlsUnchanged() {
        String text = "HTTP 429 while downloading the runtime";
        assertEquals(text, SafeText.redactUrls(text));
    }

    @Test
    void redactsSensitiveJsonFieldValuesAndKeepsFieldNames() {
        String redacted = SafeText.redactSensitiveValues("{\"access_token\":\"raw-access-token\"}");

        assertTrue(redacted.contains("access_token"));
        assertTrue(redacted.contains("***"));
        assertFalse(redacted.contains("raw-access-token"));
    }

    @Test
    void redactsSessionIdsInPlainText() {
        assertEquals("JSESSIONID=***", SafeText.redactSensitiveValues("JSESSIONID=raw-session-id"));
    }

    @Test
    void redactsSensitiveValueInHiddenHtmlInput() {
        String redacted = SafeText.redactSensitiveValues(
                "<input type=\"hidden\" name=\"csrf_token\" value=\"raw-csrf-value\">");

        assertTrue(redacted.contains("name=\"csrf_token\""));
        assertTrue(redacted.contains("value=\"***\""));
        assertFalse(redacted.contains("raw-csrf-value"));
    }

    @Test
    void redactsBearerAndJwtTokens() {
        String jwt = "eyJhbGci.eyJzdWI.eyJzdWI";
        String redacted = SafeText.redactSensitiveValues(
                "Bearer raw-bearer-token Authorization: Bearer raw-auth-token " + jwt);

        assertTrue(redacted.contains("Bearer ***"));
        assertFalse(redacted.contains("raw-bearer-token"));
        assertFalse(redacted.contains("raw-auth-token"));
        assertFalse(redacted.contains(jwt));
    }

    @Test
    void leavesOrdinaryTextUnchanged() {
        String ordinary = "The response contained no credential fields and status was 200.";
        assertEquals(ordinary, SafeText.redactSensitiveValues(ordinary));
    }

    @Test
    void redactsLongDelimiterFreeSensitiveNameRunsQuickly() {
        String repeated = "token".repeat(10_000);

        assertTimeout(Duration.ofSeconds(1),
                () -> assertEquals(repeated, SafeText.redactSensitiveValues(repeated)));
    }
}
