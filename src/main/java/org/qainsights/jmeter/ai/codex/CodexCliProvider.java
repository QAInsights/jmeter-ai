package org.qainsights.jmeter.ai.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.qainsights.jmeter.ai.claudecode.OpenAiCodexCliAdapter;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.cli.DefaultCliProcessRunner;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the locally installed Codex CLI: {@code codex login status} for the
 * authentication state, {@code codex login} / {@code codex logout} for the
 * flows the CLI owns end to end, and {@code codex exec} for non-interactive
 * prompts. Executable discovery is delegated to the existing
 * {@link OpenAiCodexCliAdapter} rather than duplicated here.
 * <p>
 * Credential files and tokens are never read, logged or copied - the CLI is the
 * only thing that touches them.
 */
public final class CodexCliProvider implements CodexProvider {

    private static final Logger log = LoggerFactory.getLogger(CodexCliProvider.class);

    /** Model-id prefix used by the model selector and the response router. */
    public static final String MODEL_PREFIX = "codex:";
    /** Selector entry meaning "whatever model the Codex CLI is configured to use". */
    public static final String DEFAULT_MODEL = "default";

    public static final String ENABLED_KEY = "jmeter.ai.codex.enabled";
    public static final String EXECUTABLE_KEY = "jmeter.ai.codex.executable";
    public static final String TIMEOUT_KEY = "jmeter.ai.codex.timeout.seconds";
    public static final String LOGIN_TIMEOUT_KEY = "jmeter.ai.codex.login.timeout.seconds";
    public static final String MODELS_KEY = "jmeter.ai.codex.models";
    public static final String SANDBOX_KEY = "jmeter.ai.codex.sandbox";

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(30);

    private final OpenAiCodexCliAdapter adapter;
    private final CliProcessRunner runner;
    private String executable;
    private boolean detectionAttempted;
    private String model = "";

    public CodexCliProvider() {
        this(new OpenAiCodexCliAdapter(), new DefaultCliProcessRunner());
    }

    CodexCliProvider(OpenAiCodexCliAdapter adapter, CliProcessRunner runner) {
        this.adapter = adapter;
        this.runner = runner;
    }

    @Override
    public String displayName() {
        return "ChatGPT / Codex";
    }

    @Override
    public boolean isEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(ENABLED_KEY, "false"));
    }

    @Override
    public String installHint() {
        return "Install the Codex CLI with 'npm install -g @openai/codex', then sign in with 'codex login'.";
    }

    @Override
    public String signInActionLabel() {
        return "Sign in with ChatGPT";
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public void setModel(String model) {
        this.model = model == null || DEFAULT_MODEL.equals(model) ? "" : model.trim();
    }

    @Override
    public boolean isInstalled() {
        return executable() != null;
    }

    /**
     * The Codex executable: the {@code jmeter.ai.codex.executable} override when
     * set, otherwise PATH discovery via {@link OpenAiCodexCliAdapter}. Cached
     * until {@link #refresh()}.
     */
    String executable() {
        String override = AiConfig.getProperty(EXECUTABLE_KEY, "").trim();
        if (!override.isEmpty()) {
            return override;
        }
        if (!detectionAttempted) {
            detectionAttempted = true;
            executable = adapter.detect() ? adapter.getBinaryPath() : null;
            if (executable != null) {
                log.info("Codex detected: {}", executable);
            } else {
                log.info("Codex CLI not found on PATH");
            }
        }
        return executable;
    }

    /** Forgets the cached executable so a newly installed CLI is picked up. */
    public void refresh() {
        detectionAttempted = false;
        executable = null;
    }

    @Override
    public CodexAuthStatus getAuthStatus() {
        String exe = executable();
        if (exe == null) {
            return CodexAuthStatus.CODEX_NOT_INSTALLED;
        }
        CliProcessResult result;
        try {
            result = runner.run(List.of(exe, "login", "status"), null, STATUS_TIMEOUT);
        } catch (CliProviderException e) {
            log.warn("Could not read the Codex authentication status: {}", e.getMessage());
            return CodexAuthStatus.UNKNOWN;
        }
        if (result.isTimedOut()) {
            log.warn("'codex login status' timed out after {} ms", result.getDurationMillis());
            return CodexAuthStatus.UNKNOWN;
        }
        CodexAuthStatus status = CodexAuthStatus.parse(result.getStdout(), result.getStderr(), result.getExitCode());
        log.info("Codex authentication provider: {}", status);
        return status;
    }

    @Override
    public CodexAuthStatus login() {
        String exe = executable();
        if (exe == null) {
            return CodexAuthStatus.CODEX_NOT_INSTALLED;
        }
        try {
            CliProcessResult result = runner.run(List.of(exe, "login"), null, loginTimeout(),
                    line -> log.info("codex login: {}", line));
            if (result.isTimedOut()) {
                log.warn("'codex login' was cancelled after the login timeout elapsed");
            }
        } catch (CliProviderException e) {
            log.warn("'codex login' failed: {}", e.getMessage());
        }
        // Never trust the login exit code: always re-verify with the CLI.
        return getAuthStatus();
    }

    @Override
    public CodexAuthStatus logout() {
        String exe = executable();
        if (exe == null) {
            return CodexAuthStatus.CODEX_NOT_INSTALLED;
        }
        try {
            runner.run(List.of(exe, "logout"), null, STATUS_TIMEOUT);
        } catch (CliProviderException e) {
            log.warn("'codex logout' failed: {}", e.getMessage());
        }
        return getAuthStatus();
    }

    @Override
    public String execute(String prompt) {
        String exe = executable();
        if (exe == null) {
            throw new CliProviderException("The Codex CLI was not found. " + installHint());
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new CliProviderException("Nothing to send to Codex: the prompt is empty.");
        }
        Path lastMessage = createLastMessageFile();
        try {
            CliProcessResult result = runner.run(buildExecCommand(exe, lastMessage), prompt, executionTimeout());
            return readAnswer(result, lastMessage);
        } finally {
            deleteQuietly(lastMessage);
        }
    }

    /**
     * {@code codex exec} reading the prompt from stdin (never interpolated into a
     * command line) with the model's own shell tools sandboxed read-only, and the
     * final answer written to {@code lastMessage} so the caller gets clean text
     * instead of progress noise.
     */
    List<String> buildExecCommand(String exe, Path lastMessage) {
        List<String> command = new ArrayList<>();
        command.add(exe);
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--color");
        command.add("never");
        command.add("--sandbox");
        command.add(AiConfig.getProperty(SANDBOX_KEY, "read-only"));
        if (!model.isEmpty()) {
            command.add("--model");
            command.add(model);
        }
        if (lastMessage != null) {
            command.add("--output-last-message");
            command.add(lastMessage.toString());
        }
        // '-' makes Codex read the instructions from stdin.
        command.add("-");
        return command;
    }

    /**
     * The answer text, preferring the {@code --output-last-message} file. Failures
     * are translated into user-facing messages; raw CLI noise only surfaces when
     * there is nothing better to show.
     */
    private String readAnswer(CliProcessResult result, Path lastMessage) {
        if (result.isTimedOut()) {
            throw new CliProviderException("Codex did not respond within "
                    + executionTimeout().toSeconds() + " seconds and was stopped. "
                    + "Increase " + TIMEOUT_KEY + " if your prompts need longer.");
        }
        String answer = readQuietly(lastMessage);
        if (answer.isEmpty() && result.isSuccess()) {
            answer = result.getStdout().trim();
        }
        if (!result.isSuccess()) {
            throw new CliProviderException(describeFailure(result));
        }
        if (answer.isEmpty()) {
            throw new CliProviderException("Codex returned an empty response. Try rephrasing your request.");
        }
        return answer.trim();
    }

    /** Maps a failed {@code codex exec} run onto a message worth showing the user. */
    private String describeFailure(CliProcessResult result) {
        String combined = (result.getStdout() + '\n' + result.getStderr()).toLowerCase(Locale.ROOT);
        if (combined.contains("not logged in") || combined.contains("please run") && combined.contains("login")
                || combined.contains("unauthorized") || combined.contains("401")) {
            return "Codex is not signed in. Run 'codex login' (or use Sign in with ChatGPT) and try again.";
        }
        if (combined.contains("unexpected argument") || combined.contains("unrecognized subcommand")) {
            return "This Codex CLI version does not support the non-interactive 'codex exec' options "
                    + "Feather Wand uses. Update it with 'npm install -g @openai/codex@latest'.";
        }
        if (combined.contains("permission denied")) {
            return "Codex could not be executed: permission denied. Check the file permissions on the codex binary.";
        }
        if (combined.contains("usage limit") || combined.contains("rate limit") || combined.contains("quota")) {
            return "Codex refused the request because a usage limit was reached. Try again later.";
        }
        String detail = result.getStderr().trim();
        if (detail.isEmpty()) {
            detail = result.getStdout().trim();
        }
        String suffix = detail.isEmpty() ? "" : " Details: " + firstLines(detail);
        return "Codex exited with code " + result.getExitCode() + "." + suffix;
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

    private static Path createLastMessageFile() {
        try {
            Path file = Files.createTempFile("featherwand-codex-", ".txt");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            log.debug("Could not create the Codex output file, falling back to stdout: {}", e.getMessage());
            return null;
        }
    }

    private static String readQuietly(Path file) {
        if (file == null) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete the Codex output file: {}", e.getMessage());
        }
    }

    /**
     * Selector entries for Codex: the CLI's own default plus any ids listed in
     * {@code jmeter.ai.codex.models} (comma-separated). Model names move fast, so
     * nothing is hard-coded.
     */
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
