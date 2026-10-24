package org.qainsights.jmeter.ai.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
