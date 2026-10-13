package org.qainsights.jmeter.ai.gui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModeNudgeTest {

    @TempDir
    Path dir;

    @Test
    void showsOnceForCloudProviderWithAgentModeOff() {
        AgentModeNudge nudge = new AgentModeNudge(dir.resolve("nested").resolve("tip"));

        assertTrue(nudge.shouldShow(true, false));
        nudge.markShown();
        assertTrue(Files.exists(dir.resolve("nested").resolve("tip")));
        assertFalse(nudge.shouldShow(true, false));
    }

    @Test
    void hiddenWhenAgentModeIsOnOrNoCloudProvider() {
        AgentModeNudge nudge = new AgentModeNudge(dir.resolve("tip"));

        assertFalse(nudge.shouldShow(true, true));
        assertFalse(nudge.shouldShow(false, false));
    }

    @Test
    void tipExplainsHowToTurnItOnWithoutEmDashes() {
        assertTrue(AgentModeNudge.TIP.contains("jmeter.ai.agent.enabled=true"));
        assertFalse(AgentModeNudge.TIP.contains("\u2014"));
    }
}
