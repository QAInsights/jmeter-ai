package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;

public class GrokCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "Grok CLI";
    }

    @Override
    public boolean detect() {
        detectedPath = findOnPath("grok");
        return detectedPath != null;
    }

    @Override
    public boolean isEnabled() {
        return AiConfig.getProperty("jmeter.ai.terminal.grok.enabled", "false").equals("true");
    }

    @Override
    public String defaultPrompt() {
        return AiConfig.getProperty("jmeter.ai.terminal.grok.prompt",
                "You are a performance engineer and testing expert in JMeter. " +
                        "Help the user to optimize the JMeter test plan, scripting, and performance related issues.");
    }
}
