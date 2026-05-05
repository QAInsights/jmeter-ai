package org.qainsights.jmeter.ai.claudecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseCliAdapter implements AiCliAdapter {
    protected static final Logger log = LoggerFactory.getLogger(BaseCliAdapter.class);
    protected String detectedPath;

    @Override
    public String getBinaryPath() {
        return detectedPath;
    }

    protected String findOnPath(String binaryName) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        try {
            String[] cmd = isWindows
                    ? new String[]{"cmd.exe", "/c", "where", binaryName}
                    : new String[]{"/bin/sh", "-c", "which " + binaryName};

            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                if (isWindows) {
                    java.util.List<String> candidates = new java.util.ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) candidates.add(line);
                    }
                    int exitCode = process.waitFor();
                    if (exitCode == 0 && !candidates.isEmpty()) {
                        for (String c : candidates) {
                            String lower = c.toLowerCase();
                            if (lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".bat")) {
                                return c;
                            }
                        }
                        return candidates.get(0);
                    }
                } else {
                    String line = reader.readLine();
                    int exitCode = process.waitFor();
                    if (exitCode == 0 && line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error searching PATH for {}: {}", binaryName, e.getMessage());
        }
        return null;
    }

    @Override
    public List<String> buildCommand(String workingDirectory) {
        List<String> command = new ArrayList<>();
        command.add(detectedPath);
        return command;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String defaultPrompt() {
        return "You are a performance engineer and testing expert in JMeter. " +
                "Help the user to optimize the JMeter test plan, scripting, and performance related issues.";
    }

}
