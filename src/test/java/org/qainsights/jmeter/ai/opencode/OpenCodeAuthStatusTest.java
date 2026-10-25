package org.qainsights.jmeter.ai.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenCodeAuthStatusTest {

    @Test
    void exposesTheExpectedLabelsAndAvailability() {
        assertEquals("\u2713 Ready", OpenCodeAuthStatus.READY.label());
        assertTrue(OpenCodeAuthStatus.READY.isInstalled());
        assertTrue(OpenCodeAuthStatus.READY.canRunPrompts());

        assertEquals("OpenCode CLI not installed", OpenCodeAuthStatus.CLI_NOT_INSTALLED.label());
        assertFalse(OpenCodeAuthStatus.CLI_NOT_INSTALLED.isInstalled());
        assertFalse(OpenCodeAuthStatus.CLI_NOT_INSTALLED.canRunPrompts());

        assertEquals("Unable to determine status", OpenCodeAuthStatus.UNKNOWN.label());
        assertTrue(OpenCodeAuthStatus.UNKNOWN.isInstalled());
        assertFalse(OpenCodeAuthStatus.UNKNOWN.canRunPrompts());
    }
}
