package org.qainsights.jmeter.ai.claudecode;

public class OpenAiCodexCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "OpenAI Codex CLI";
    }

    @Override
    public boolean detect() {
        detectedPath = findOnPath("codex");
        return detectedPath != null;
    }
}
