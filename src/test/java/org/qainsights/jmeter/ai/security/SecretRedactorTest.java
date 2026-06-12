package org.qainsights.jmeter.ai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SecretRedactor}. Redaction is on by default (no JMeter
 * properties loaded), so these exercise the default-enabled behaviour.
 */
class SecretRedactorTest {

    @Test
    void masksPropertyStyleSecretButKeepsKey() {
        String in = "  | HTTPSampler.password = s3cr3tV4lue\n  | HTTPSampler.path = /api/login";
        String out = SecretRedactor.redact(in);

        assertFalse(out.contains("s3cr3tV4lue"), out);
        assertTrue(out.contains("HTTPSampler.password = " + SecretRedactor.MASK), out);
        // Non-secret values are untouched.
        assertTrue(out.contains("/api/login"), out);
    }

    @Test
    void masksJsonStyleTokenField() {
        String in = "{\"api_key\":\"AKIA1234567890\",\"region\":\"us-east-1\"}";
        String out = SecretRedactor.redact(in);

        assertFalse(out.contains("AKIA1234567890"), out);
        assertTrue(out.contains("us-east-1"), out);
    }

    @Test
    void masksBearerToken() {
        String in = "Authorization: Bearer abc123DEF456ghi789";
        String out = SecretRedactor.redact(in);

        assertFalse(out.contains("abc123DEF456ghi789"), out);
        assertTrue(out.contains(SecretRedactor.MASK), out);
    }

    @Test
    void masksStandaloneJwt() {
        String in = "cookie value eyJhbGciOiJIUzI1Ni19.eyJzdWIiOiIxMjM0NTY.SflKxwRJSMeKKF2QT4";
        String out = SecretRedactor.redact(in);

        assertFalse(out.contains("eyJhbGciOiJIUzI1Ni19"), out);
        assertTrue(out.contains(SecretRedactor.MASK), out);
    }

    @Test
    void leavesBenignTextUnchanged() {
        String in = "[ThreadGroup] Login Flow\n  | num_threads = 100\n  | ramp_time = 30";
        assertEquals(in, SecretRedactor.redact(in));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(SecretRedactor.redact(null));
        assertEquals("", SecretRedactor.redact(""));
    }

    @Test
    void isEnabledByDefault() {
        assertTrue(SecretRedactor.isEnabled());
    }
}
