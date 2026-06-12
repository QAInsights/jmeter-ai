package org.qainsights.jmeter.ai.claudecode;

import org.qainsights.jmeter.ai.utils.AiConfig;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the <strong>AWS Kiro</strong> agentic CLI ({@code kiro-cli}).
 *
 * <p>Kiro is not found by a plain {@code PATH} scan in two common situations on
 * Windows: its binary is {@code kiro-cli.exe} (not {@code claude}/{@code codex}/…),
 * and its installer adds {@code %USERPROFILE%\.kiro\bin} to the user PATH only for
 * <em>newly</em> launched processes — an already-running JMeter never sees it.
 * This adapter therefore resolves the binary in three steps:
 *
 * <ol>
 *   <li>an explicit {@code jmeter.ai.terminal.kiro.path} override;</li>
 *   <li>the system {@code PATH} ({@code kiro-cli}, then {@code kiro});</li>
 *   <li>Kiro's well-known default install locations.</li>
 * </ol>
 *
 * <p>The terminal launches Kiro interactively with {@code kiro-cli chat}; the test
 * plan context is supplied via the {@code AGENTS.md}/{@code KIRO.md} files that
 * {@code ClaudeCodePanel} writes into the working directory.
 */
public class KiroCliAdapter extends BaseCliAdapter {

    @Override
    public String getName() {
        return "AWS Kiro";
    }

    @Override
    public boolean detect() {
        // 1) Explicit override wins.
        String override = AiConfig.getProperty("jmeter.ai.terminal.kiro.path", "");
        if (override != null && !override.trim().isEmpty()) {
            File f = new File(expandHome(override.trim()));
            if (f.isFile()) {
                detectedPath = f.getAbsolutePath();
                return true;
            }
        }

        // 2) System PATH (handles kiro-cli and the shorter kiro alias).
        String onPath = findOnPath("kiro-cli");
        if (onPath == null) {
            onPath = findOnPath("kiro");
        }
        if (onPath != null) {
            detectedPath = onPath;
            return true;
        }

        // 3) Kiro's default install locations (covers the stale-PATH case).
        for (File candidate : defaultInstallCandidates()) {
            if (candidate.isFile()) {
                detectedPath = candidate.getAbsolutePath();
                return true;
            }
        }
        return false;
    }

    private List<File> defaultInstallCandidates() {
        List<File> out = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            out.add(Paths.get(home, ".kiro", "bin", "kiro-cli.exe").toFile());
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.trim().isEmpty()) {
                out.add(Paths.get(localAppData, "Programs", "kiro", "bin", "kiro-cli.exe").toFile());
                out.add(Paths.get(localAppData, "kiro", "bin", "kiro-cli.exe").toFile());
            }
        } else {
            out.add(Paths.get(home, ".kiro", "bin", "kiro-cli").toFile());
            out.add(new File("/usr/local/bin/kiro-cli"));
            out.add(new File("/opt/kiro/bin/kiro-cli"));
        }
        return out;
    }

    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home", "") + path.substring(1);
        }
        return path;
    }

    @Override
    public List<String> buildCommand(String workingDirectory) {
        // Launch Kiro's interactive terminal UI. Kiro reads AGENTS.md / KIRO.md
        // from the working directory for test-plan context.
        List<String> command = super.buildCommand(workingDirectory);
        command.add("chat");
        appendTrustFlags(command);
        return command;
    }

    @Override
    public boolean supportsHeadless() {
        return true;
    }

    @Override
    public List<String> buildHeadlessCommand(String prompt, String workingDirectory) {
        // Kiro CLI 2.0 headless: kiro-cli chat --no-interactive [trust] "<prompt>"
        // Requires KIRO_API_KEY in the environment (Pro tier+).
        List<String> command = super.buildCommand(workingDirectory);
        command.add("chat");
        command.add("--no-interactive");
        appendTrustFlags(command);
        command.add(prompt == null ? "" : prompt);
        return command;
    }

    /**
     * Append the governed tool-trust policy. Defaults to read-only tools so the
     * agent cannot mutate the test plan or filesystem without an explicit opt-in.
     * Admins can lock either property via a managed user.properties.
     */
    private void appendTrustFlags(List<String> command) {
        if (AiConfig.getProperty("jmeter.ai.terminal.kiro.trust_all_tools", "false")
                .equalsIgnoreCase("true")) {
            command.add("--trust-all-tools");
        } else {
            String trustTools = AiConfig
                    .getProperty("jmeter.ai.terminal.kiro.trust_tools", "read,grep,fs_read").trim();
            if (!trustTools.isEmpty()) {
                command.add("--trust-tools=" + trustTools);
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return AiConfig.getProperty("jmeter.ai.terminal.kiro.enabled", "true").equals("true");
    }

    @Override
    public String defaultPrompt() {
        return AiConfig.getProperty("jmeter.ai.terminal.kiro.prompt",
                "You are an AI assistant integrated into Apache JMeter via the FeatherWand plugin. " +
                        "You have access to the current JMeter test plan structure. " +
                        "Help the user with performance testing tasks: analyzing and optimizing JMeter " +
                        "test plans, creating test elements, debugging issues, performance best practices, " +
                        "and writing/debugging Groovy and Java (JSR223) code.");
    }
}
