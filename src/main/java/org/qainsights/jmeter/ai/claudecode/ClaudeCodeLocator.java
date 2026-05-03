package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to locate the Claude Code CLI binary across
 * Windows, macOS, and Linux.
 *
 * Claude Code is typically installed via:
 * npm install -g @anthropic-ai/claude-code
 *
 * The binary name is "claude" (or "claude.cmd" on Windows).
 */
public class ClaudeCodeLocator {
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeLocator.class);

    private ClaudeCodeLocator() {
        // utility class
    }

    /**
     * Finds the full path to the Claude Code binary.
     *
     * @return the absolute path to the claude binary, or "claude" if not found
     *         (falling back to PATH resolution at process start time)
     */
    public static String findClaudeCodeBinary() {

        // Check whether ClaudeCode is enabled or not
        String isClaudeCodeEnabled = AiConfig.getProperty("jmeter.ai.terminal.claudecode.enabled",  "false");

        if(isClaudeCodeEnabled.equals("true")) {

            // Getting it from property file
            String claudeCodePathFromProperty = AiConfig.getProperty("jmeter.ai.terminal.claudecode.path", "");

            if(!claudeCodePathFromProperty.isEmpty()) {
                File fileFromProperty = new File(claudeCodePathFromProperty);
                if(fileFromProperty.exists() && fileFromProperty.canExecute()) {
                    log.info("Found Claude Code binary from property: {}", fileFromProperty.getAbsolutePath());
                    return claudeCodePathFromProperty;
                }
            }


            String osName = System.getProperty("os.name", "").toLowerCase();

            List<String> candidates = new ArrayList<>();

            if (osName.contains("win")) {
                candidates.addAll(getWindowsCandidates());
            } else if (osName.contains("mac")) {
                candidates.addAll(getMacCandidates());
            } else {
                candidates.addAll(getLinuxCandidates());
            }

            // Check each candidate path
            for (String candidate : candidates) {
                File file = new File(candidate);
                if (file.exists() && file.canExecute()) {
                    log.info("Found Claude Code binary at: {}", file.getAbsolutePath());
                    return file.getAbsolutePath();
                }
            }

            // Try "which" / "where" as last resort
            String fromPath = findOnPath(osName.contains("win"));
            if (fromPath != null) {
                log.info("Found Claude Code on PATH: {}", fromPath);
                return fromPath;
            }

            log.warn("Claude Code binary not found in common locations. Falling back to 'claude' (PATH lookup).");
            return osName.contains("win") ? "claude.cmd" : "claude";
        }
        else {
            return null;
        }
    }

    private static List<String> getWindowsCandidates() {


        List<String> paths = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String localAppData = System.getenv("LOCALAPPDATA");
        String userProfile = System.getenv("USERPROFILE");

        if (appData != null) {
            paths.add(appData + "\\npm\\claude.cmd");
        }
        if (localAppData != null) {
            paths.add(localAppData + "\\npm\\claude.cmd");
        }
        if (userProfile != null) {
            paths.add(userProfile + "\\.npm-global\\claude.cmd");
            paths.add(userProfile + "\\AppData\\Roaming\\npm\\claude.cmd");
        }
        // Common nvm-windows paths
        String nvmHome = System.getenv("NVM_HOME");
        if (nvmHome != null) {
            paths.add(nvmHome + "\\..\\nodejs\\claude.cmd");
        }
        // fnm / volta / nvm symlink directories
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            paths.add(programFiles + "\\nodejs\\claude.cmd");
        }
        return paths;
    }

    private static List<String> getMacCandidates() {
        List<String> paths = new ArrayList<>();
        String home = System.getProperty("user.home");

        paths.add("~/.local/bin/claude");
        paths.add("/usr/local/bin/claude");
        paths.add("/opt/homebrew/bin/claude");
        if (home != null) {
            paths.add(home + "/.npm-global/bin/claude");
            paths.add(home + "/.nvm/versions/node/default/bin/claude");
            paths.add(home + "/.local/bin/claude");
        }
        paths.add("/usr/bin/claude");
        log.info("Claude Code binary candidates: {}", paths);
        return paths;
    }

    private static List<String> getLinuxCandidates() {
        List<String> paths = new ArrayList<>();
        String home = System.getProperty("user.home");

        paths.add("/usr/local/bin/claude");
        if (home != null) {
            paths.add(home + "/.npm-global/bin/claude");
            paths.add(home + "/.nvm/versions/node/default/bin/claude");
            paths.add(home + "/.local/bin/claude");
        }
        paths.add("/usr/bin/claude");
        paths.add("/snap/bin/claude");
        return paths;
    }

    /**
     * Attempts to find 'claude' on the system PATH using where (Windows) or which
     * (Unix).
     */
    private static String findOnPath(boolean isWindows) {
        try {
            String[] cmd = isWindows
                    ? new String[] { "cmd.exe", "/c", "where", "claude" }
                    : new String[] { "/bin/sh", "-c", "which claude" };

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
            log.debug("Error searching PATH for claude: {}", e.getMessage());
        }
        return null;
    }

}
