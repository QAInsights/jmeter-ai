package org.qainsights.jmeter.ai.claudecode;

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
}
