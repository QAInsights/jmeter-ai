package org.qainsights.jmeter.ai.claudecode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.cli.DefaultCliProcessRunner;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the locally installed Claude Code CLI: {@code claude auth status --json}
 * for the authentication state, {@code claude auth login} / {@code claude auth logout}
 * for the flows the CLI owns, and {@code claude -p} for non-interactive prompts.
 * Executable discovery is delegated to the existing {@link ClaudeCodeCliAdapter}.
 * <p>
 * This is the subscription counterpart of {@code ClaudeService}: that one keeps
 * using {@code anthropic.api.key} against the API, this one reuses whatever
 * account the Claude Code CLI is already signed into.
 */
public final class ClaudeCodeCliProvider implements SubscriptionCliProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCliProvider.class);

    /** Model-id prefix used by the model selector and the response router. */
    public static final String MODEL_PREFIX = "claude-code:";
    /** Selector entry meaning "whatever model the Claude Code CLI is configured to use". */
    public static final String DEFAULT_MODEL = "default";

    public static final String ENABLED_KEY = "jmeter.ai.claudecode.provider.enabled";
    public static final String EXECUTABLE_KEY = "jmeter.ai.claudecode.executable";
    public static final String TIMEOUT_KEY = "jmeter.ai.claudecode.timeout.seconds";
    public static final String LOGIN_TIMEOUT_KEY = "jmeter.ai.claudecode.login.timeout.seconds";
    public static final String MODELS_KEY = "jmeter.ai.claudecode.models";

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(30);

    private final ClaudeCodeCliAdapter adapter;
    private final CliProcessRunner runner;
    private String executable;
    private boolean detectionAttempted;
    private volatile String model = "";

    public ClaudeCodeCliProvider() {
        this(new ClaudeCodeCliAdapter(), new DefaultCliProcessRunner());
    }

    ClaudeCodeCliProvider(ClaudeCodeCliAdapter adapter, CliProcessRunner runner) {
        this.adapter = adapter;
        this.runner = runner;
    }

    @Override
    public String displayName() {
        return "Claude Code";
    }

    @Override
    public boolean isEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(ENABLED_KEY, "false"));
    }

    @Override
    public String installHint() {
        return "Install the Claude Code CLI with 'npm install -g @anthropic-ai/claude-code', "
                + "then sign in with 'claude auth login'.";
    }

    @Override
    public String signInActionLabel() {
        return "Sign in with Claude";
    }

    @Override
    public String modelPrefix() {
        return MODEL_PREFIX;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public void setModel(String model) {
        this.model = normalizeModel(model);
    }

    private static String normalizeModel(String model) {
        return model == null || DEFAULT_MODEL.equals(model) ? "" : model.trim();
    }

    @Override
    public boolean isInstalled() {
        return executable() != null;
    }

    /**
     * The Claude executable: the {@code jmeter.ai.claudecode.executable} override
     * when set, otherwise PATH discovery via {@link ClaudeCodeCliAdapter}.
     */
    synchronized String executable() {
        String override = AiConfig.getProperty(EXECUTABLE_KEY, "").trim();
        if (!override.isEmpty()) {
            return override;
        }
        if (!detectionAttempted) {
            detectionAttempted = true;
            executable = adapter.detect() ? adapter.getBinaryPath() : null;
            if (executable != null) {
                log.info("Claude Code detected: {}", executable);
            } else {
                log.info("Claude Code CLI not found on PATH");
            }
        }
        return executable;
    }

    /** Forgets the cached executable so a newly installed CLI is picked up. */
    @Override
    public synchronized void refresh() {
        detectionAttempted = false;
        executable = null;
    }

    @Override
    public ClaudeCodeAuthStatus getAuthStatus() {
        String exe = executable();
        if (exe == null) {
            return ClaudeCodeAuthStatus.CLI_NOT_INSTALLED;
        }
        CliProcessResult result;
        try {
            result = runner.run(List.of(exe, "auth", "status", "--json"), null, STATUS_TIMEOUT);
        } catch (CliProviderException e) {
            log.warn("Could not read the Claude Code authentication status: {}", e.getMessage());
            return ClaudeCodeAuthStatus.UNKNOWN;
        }
        if (result.isTimedOut()) {
            log.warn("'claude auth status' timed out after {} ms", result.getDurationMillis());
            return ClaudeCodeAuthStatus.UNKNOWN;
        }
        ClaudeCodeAuthStatus status = ClaudeCodeAuthStatus.parse(result.getStdout(), result.getStderr(),
                result.getExitCode());
        log.info("Claude Code authentication provider: {}", status);
        return status;
    }

    @Override
    public ClaudeCodeAuthStatus login() {
        String exe = executable();
        if (exe == null) {
            return ClaudeCodeAuthStatus.CLI_NOT_INSTALLED;
        }
        try {
            CliProcessResult result = runner.run(List.of(exe, "auth", "login", "--claudeai"), null, loginTimeout(),
                    line -> log.info("claude auth login: {}", line));
            if (result.isTimedOut()) {
                log.warn("'claude auth login' was cancelled after the login timeout elapsed");
            }
        } catch (CliProviderException e) {
            log.warn("'claude auth login' failed: {}", e.getMessage());
        }
        // Never trust the login exit code: always re-verify with the CLI.
        return getAuthStatus();
    }

    @Override
    public ClaudeCodeAuthStatus logout() {
        String exe = executable();
        if (exe == null) {
            return ClaudeCodeAuthStatus.CLI_NOT_INSTALLED;
        }
        try {
            runner.run(List.of(exe, "auth", "logout"), null, STATUS_TIMEOUT);
        } catch (CliProviderException e) {
            log.warn("'claude auth logout' failed: {}", e.getMessage());
        }
        return getAuthStatus();
    }

    @Override
    public String execute(String prompt) {
        return execute(prompt, model);
    }

    @Override
    public String execute(String prompt, String model) {
        String exe = executable();
        if (exe == null) {
            throw new CliProviderException("The Claude Code CLI was not found. " + installHint());
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new CliProviderException("Nothing to send to Claude Code: the prompt is empty.");
        }
        CliProcessResult result = runner.run(buildExecCommand(exe, normalizeModel(model)), prompt, executionTimeout());
        if (result.isTimedOut()) {
            throw new CliProviderException("Claude Code did not respond within "
                    + executionTimeout().toSeconds() + " seconds and was stopped. "
                    + "Increase " + TIMEOUT_KEY + " if your prompts need longer.");
        }
        if (!result.isSuccess()) {
            throw new CliProviderException(describeFailure(result));
        }
        String answer = result.getStdout().trim();
        if (answer.isEmpty()) {
            throw new CliProviderException("Claude Code returned an empty response. Try rephrasing your request.");
        }
        return answer;
    }

    /** {@code claude -p} reading the prompt from stdin, printing plain text. */
    List<String> buildExecCommand(String exe, String model) {
        List<String> command = new ArrayList<>();
        command.add(exe);
        command.add("-p");
        command.add("--output-format");
        command.add("text");
        if (!model.isEmpty()) {
            command.add("--model");
            command.add(model);
        }
        return command;
    }

    /** Maps a failed {@code claude -p} run onto a message worth showing the user. */
    private String describeFailure(CliProcessResult result) {
        String combined = (result.getStdout() + '\n' + result.getStderr()).toLowerCase(Locale.ROOT);
        if (combined.contains("not logged in") || combined.contains("/login")
                || combined.contains("unauthorized") || combined.contains("invalid api key")) {
            return "Claude Code is not signed in. Run 'claude auth login' (or use Sign in with Claude) "
                    + "and try again.";
        }
        if (combined.contains("unknown option") || combined.contains("unknown command")) {
            return "This Claude Code CLI version does not support the options Feather Wand uses. "
                    + "Update it with 'npm install -g @anthropic-ai/claude-code@latest'.";
        }
        if (combined.contains("permission denied")) {
            return "Claude Code could not be executed: permission denied. "
                    + "Check the file permissions on the claude binary.";
        }
        if (combined.contains("usage limit") || combined.contains("rate limit") || combined.contains("quota")) {
            return "Claude Code refused the request because a usage limit was reached. Try again later.";
        }
        String detail = result.getStderr().trim();
        if (detail.isEmpty()) {
            detail = result.getStdout().trim();
        }
        String suffix = detail.isEmpty() ? "" : " Details: " + firstLines(detail);
        return "Claude Code exited with code " + result.getExitCode() + "." + suffix;
    }

    private static String firstLines(String text) {
        String[] lines = text.split("\\R");
        int limit = Math.min(lines.length, 3);
        return String.join(" ", java.util.Arrays.copyOfRange(lines, 0, limit));
    }

    private Duration executionTimeout() {
        return Duration.ofSeconds(parsePositiveLong(AiConfig.getProperty(TIMEOUT_KEY, "120"), 120L));
    }

    private Duration loginTimeout() {
        return Duration.ofSeconds(parsePositiveLong(AiConfig.getProperty(LOGIN_TIMEOUT_KEY, "300"), 300L));
    }

    static long parsePositiveLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Selector entries for Claude Code: the CLI's own default plus any ids listed
     * in {@code jmeter.ai.claudecode.models} (comma-separated).
     */
    @Override
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        models.add(DEFAULT_MODEL);
        for (String id : AiConfig.getProperty(MODELS_KEY, "").split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty() && !models.contains(trimmed)) {
                models.add(trimmed);
            }
        }
        return models;
    }
}
