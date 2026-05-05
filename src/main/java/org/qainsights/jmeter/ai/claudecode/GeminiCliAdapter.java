package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;

public class GeminiCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "Gemini CLI";
    }

    @Override
    public boolean detect() {
        detectedPath = findOnPath("gemini");
        return detectedPath != null;
    }

    @Override
    public boolean isEnabled() {
        return AiConfig.getProperty("jmeter.ai.terminal.gemini.enabled", "false").equals("true");
    }

    @Override
    public String defaultPrompt() {
        return AiConfig.getProperty("jmeter.ai.terminal.gemini.prompt",
                "You are a performance engineer and testing expert in JMeter. \" +\n" +
                        "                \"Help the user to optimize the JMeter test plan, scripting, and performance related issues.");
    }

}
