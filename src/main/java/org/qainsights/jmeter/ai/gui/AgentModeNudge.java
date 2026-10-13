package org.qainsights.jmeter.ai.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time welcome tip for installs with a working cloud provider but Agent Mode switched
 * off. It only explains how to turn the mode on; it never changes the setting. Showing it
 * writes a marker file so the tip does not come back.
 */
final class AgentModeNudge {

    private static final Logger log = LoggerFactory.getLogger(AgentModeNudge.class);

    static final String TIP = "\n\n**Tip: let Feather Wand edit the plan for you.** Agent Mode can add and "
            + "configure elements, correlate dynamic values, run the test and fix what fails, all from this "
            + "chat. To turn it on, add `jmeter.ai.agent.enabled=true` to `user.properties`, restart JMeter, "
            + "then pick **Agent** under the message box. You'll see this tip only once.";

    private final Path marker;

    AgentModeNudge() {
        this(Path.of(System.getProperty("user.home"), ".jmeter-ai", "agent-mode-tip-shown"));
    }

    AgentModeNudge(Path marker) {
        this.marker = marker;
    }

    /** True when a cloud provider is ready, Agent Mode is off and the tip was never shown. */
    boolean shouldShow(boolean cloudProviderReady, boolean agentEnabled) {
        return cloudProviderReady && !agentEnabled && !Files.exists(marker);
    }

    void markShown() {
        try {
            Files.createDirectories(marker.getParent());
            if (!Files.exists(marker)) {
                Files.createFile(marker);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not record that the Agent Mode tip was shown", e);
        }
    }
}
