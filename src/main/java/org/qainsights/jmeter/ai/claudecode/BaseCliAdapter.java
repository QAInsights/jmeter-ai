package org.qainsights.jmeter.ai.claudecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

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
                    ? new String[] { "cmd.exe", "/c", "where", binaryName }
                    : new String[] { "/bin/sh", "-c", "which " + binaryName };

            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode == 0 && line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            log.debug("Error searching PATH for {}: {}", binaryName, e.getMessage());
        }
        return null;
    }

    @Override
    public String toString() {
        return getName();
    }
}
