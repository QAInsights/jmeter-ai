package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;

public class CopilotCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "GitHub Copilot CLI";
    }

    @Override
    public boolean detect() {
        detectedPath = findOnPath("copilot");
        return detectedPath != null;
    }

    @Override
    public String enablementProperty() {
        return "jmeter.ai.terminal.copilot.enabled";
    }

    @Override
    public boolean isEnabled() {
        return AiConfig.getProperty(enablementProperty(), "false").equals("true");
    }

    @Override
    public String defaultPrompt() {
        return AiConfig.getProperty("jmeter.ai.terminal.copilot.prompt",
                "You are a performance engineer and testing expert in JMeter. " +
                        "Help the user to optimize the JMeter test plan, scripting, and performance related issues.");
    }
}
