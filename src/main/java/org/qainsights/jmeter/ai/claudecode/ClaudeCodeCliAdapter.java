package org.qainsights.jmeter.ai.claudecode;

import java.io.File;

public class ClaudeCodeCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "Claude Code";
    }

    @Override
    public boolean detect() {
        String claudePath = ClaudeCodeLocator.findClaudeCodeBinary();
        if (claudePath != null) {
            File f = new File(claudePath);
            if (f.exists() && f.canExecute()) {
                detectedPath = claudePath;
                return true;
            } else {
                String onPath = findOnPath("claude");
                if (onPath != null) {
                    detectedPath = onPath;
                    return true;
                }
            }
        } else {
            String onPath = findOnPath("claude");
            if (onPath != null) {
                detectedPath = onPath;
                return true;
            }
        }
        return false;
    }
}
