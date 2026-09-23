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
        boolean isWindows = isWindows();
        try {
            Process process = new ProcessBuilder(lookupCommand(binaryName, isWindows))
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                if (isWindows) {
                    List<String> candidates = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) candidates.add(line);
                    }
                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        return pickWindowsCandidate(candidates);
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

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** The {@code where}/{@code which} invocation used to locate {@code binaryName}. */
    static String[] lookupCommand(String binaryName, boolean isWindows) {
        return isWindows
                ? new String[]{"cmd.exe", "/c", "where", binaryName}
                : new String[]{"/bin/sh", "-c", "which " + binaryName};
    }

    /**
     * {@code where} may list several matches (e.g. a shim script next to its
     * {@code .cmd} launcher); prefer the first directly executable one, else the
     * first match. {@code null} when there are none.
     */
    static String pickWindowsCandidate(List<String> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        for (String c : candidates) {
            String lower = c.toLowerCase();
            if (lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".bat")) {
                return c;
            }
        }
        return candidates.get(0);
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
