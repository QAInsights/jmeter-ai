package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;

import java.io.File;
import java.util.List;

public class ClaudeCodeCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "Claude Code";
    }

    @Override
    public boolean detect() {
        String claudePath = findOnPath("claude");
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

    @Override
    public List<String> buildCommand(String workingDirectory) {
        List<String> command = super.buildCommand(workingDirectory);
        if (workingDirectory != null) {
            command.add("--add-dir");
            command.add(workingDirectory);
        }
        return command;
    }

    @Override
    public boolean isEnabled() {
        return AiConfig.getProperty("jmeter.ai.terminal.claudecode.enabled", "true").equals("true");
    }

    @Override
    public String defaultPrompt() {
        return AiConfig.getProperty("jmeter.ai.terminal.claudecode.prompt",
                "You are a performance engineer and testing expert in JMeter. \" +\n" +
                        "                \"Help the user to optimize the JMeter test plan, scripting, and performance related issues.");
    }
}
