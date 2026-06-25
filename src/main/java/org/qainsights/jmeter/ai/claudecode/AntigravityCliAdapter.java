package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;

public class AntigravityCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "Antigravity CLI";
    }

    @Override
    public boolean detect() {
        detectedPath = findOnPath("agy");
        return detectedPath != null;
    }

    @Override
    public boolean isEnabled() {
        return AiConfig.getProperty("jmeter.ai.terminal.antigravity.enabled", "false").equals("true");
    }

    @Override
    public String defaultPrompt() {
        return AiConfig.getProperty("jmeter.ai.terminal.antigravity.prompt",
                "You are a performance engineer and testing expert in JMeter. " +
                        "Help the user to optimize the JMeter test plan, scripting, and performance related issues.");
    }

}
