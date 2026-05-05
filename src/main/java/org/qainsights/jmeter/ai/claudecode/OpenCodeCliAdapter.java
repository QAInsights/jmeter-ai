package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;

public class OpenCodeCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "OpenCode";
    }

    @Override
    public boolean detect() {
        detectedPath = findOnPath("opencode");
        return detectedPath != null;
    }

    @Override
    public boolean isEnabled() {
        return AiConfig.getProperty("jmeter.ai.terminal.opencode.enabled", "false").equals("true");
    }

    @Override
    public String defaultPrompt() {
        return AiConfig.getProperty("jmeter.ai.terminal.opencode.prompt",
                "You are a performance engineer and testing expert in JMeter. \" +\n" +
                        "                \"Help the user to optimize the JMeter test plan, scripting, and performance related issues.");
    }
}
