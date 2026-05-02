package org.qainsights.jmeter.ai.claudecode;

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
}
