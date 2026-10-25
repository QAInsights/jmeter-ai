package org.qainsights.jmeter.ai.opencode;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

import org.qainsights.jmeter.ai.claudecode.OpenCodeCliAdapter;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.cli.DefaultCliProcessRunner;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs the local OpenCode CLI for Chat and Agent Mode. Requests use a fresh,
 * empty working directory and a restrictive permission configuration; OpenCode
 * keeps ownership of provider authentication.
 */
public final class OpenCodeCliProvider implements SubscriptionCliProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeCliProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9._-]+/\\S+$");
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(60);

    public static final String MODEL_PREFIX = "opencode:";
    public static final String DEFAULT_MODEL = "default";
    public static final String ENABLED_KEY = "jmeter.ai.opencode.enabled";
    public static final String EXECUTABLE_KEY = "jmeter.ai.opencode.executable";
    public static final String TIMEOUT_KEY = "jmeter.ai.opencode.timeout.seconds";
    public static final String MODELS_KEY = "jmeter.ai.opencode.models";
    public static final String SAFE_CONFIG =
            "{\"permission\":{\"*\":\"deny\",\"read\":\"allow\",\"glob\":\"allow\","
                    + "\"grep\":\"allow\",\"list\":\"allow\",\"external_directory\":\"deny\"}}";

    private final OpenCodeCliAdapter adapter;
    private final CliProcessRunner runner;
    private String executable;
    private boolean detectionAttempted;
    private volatile List<String> discoveredModels;
    private volatile String model = "";

    public OpenCodeCliProvider() {
        this(new OpenCodeCliAdapter(), new DefaultCliProcessRunner());
    }

    OpenCodeCliProvider(OpenCodeCliAdapter adapter, CliProcessRunner runner) {
        this.adapter = adapter;
        this.runner = runner;
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    @Override
    public boolean isEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(ENABLED_KEY, "false"));
    }

    @Override
    public String installHint() {
        return "Install OpenCode with 'npm install -g opencode-ai', then add providers with "
                + "'opencode auth login' in the AI CLI terminal tab. Its free models work without signing in.";
    }

    @Override
    public String signInActionLabel() {
        return "Add providers in OpenCode";
    }

    @Override
    public boolean managesSignIn() {
        return false;
    }

    @Override
    public String signInHint() {
        return "Add providers with 'opencode auth login' in the AI CLI terminal tab.";
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
        if (model == null || model.isBlank()) {
            return "";
        }
        String trimmed = model.trim();
        return DEFAULT_MODEL.equals(trimmed) ? "" : trimmed;
    }

    @Override
    public boolean isInstalled() {
        return executable() != null;
    }

    synchronized String executable() {
        String override = AiConfig.getProperty(EXECUTABLE_KEY, "");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        if (!detectionAttempted) {
            detectionAttempted = true;
            executable = adapter.detect() ? adapter.getBinaryPath() : null;
            if (executable != null) {
                log.info("OpenCode detected: {}", executable);
            } else {
                log.info("OpenCode CLI not found on PATH");
            }
        }
        return executable;
    }

    @Override
    public void refresh() {
        synchronized (this) {
            detectionAttempted = false;
            executable = null;
            discoveredModels = null;
        }
        String exe = executable();
        if (exe != null) {
            discoverModels(exe);
        }
    }

    @Override
    public OpenCodeAuthStatus getAuthStatus() {
        String exe = executable();
        if (exe == null) {
            return OpenCodeAuthStatus.CLI_NOT_INSTALLED;
        }
        try {
            CliProcessResult result = runner.run(List.of(exe, "--version"), null, STATUS_TIMEOUT);
            return result.isSuccess() ? OpenCodeAuthStatus.READY : OpenCodeAuthStatus.UNKNOWN;
        } catch (RuntimeException e) {
            log.warn("Could not read the OpenCode CLI status: {}", e.getMessage());
            return OpenCodeAuthStatus.UNKNOWN;
        }
    }

    @Override
    public OpenCodeAuthStatus login() {
        return getAuthStatus();
    }

    @Override
    public OpenCodeAuthStatus logout() {
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
            throw new CliProviderException("The OpenCode CLI was not found. " + installHint());
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new CliProviderException("Nothing to send to OpenCode: the prompt is empty.");
        }

        Path tempDirectory = createTempDirectory();
        try {
            String requestModel = normalizeModel(model);
            CliProcessResult result = runner.run(
                    buildRunCommand(exe, tempDirectory, requestModel),
                    prompt,
                    executionTimeout(),
                    Map.of("OPENCODE_CONFIG_CONTENT", SAFE_CONFIG));
            return readAnswer(result, requestModel);
        } finally {
            deleteTreeQuietly(tempDirectory);
        }
    }

    List<String> buildRunCommand(String exe, Path directory, String model) {
        List<String> command = new ArrayList<>(List.of(
                exe, "run", "--pure", "--format", "json", "--dir", directory.toString()));
        if (!model.isEmpty()) {
            command.add("-m");
            command.add(model);
        }
        return command;
    }

    private String readAnswer(CliProcessResult result, String model) {
        if (result.isTimedOut()) {
            throw new CliProviderException("OpenCode did not respond within "
                    + executionTimeout().toSeconds() + " seconds and was stopped. Increase "
                    + TIMEOUT_KEY + " if your prompts need longer.");
        }

        ParsedOutput output = parseOutput(result.getStdout());
        if (output.errorName != null || output.errorMessage != null) {
            throw new CliProviderException(describeError(
                    output.errorName, output.errorMessage, result, model));
        }
        if (!result.isSuccess()) {
            throw new CliProviderException(describeFailure(result));
        }
        if (output.answer.isBlank()) {
            throw new CliProviderException(
                    "OpenCode returned an empty response. Try rephrasing your request.");
        }
        return output.answer.trim();
    }

    private String describeError(String errorName, String errorMessage,
                                 CliProcessResult result, String model) {
        String name = errorName == null ? "" : errorName.toLowerCase(Locale.ROOT);
        String message = errorMessage == null ? "" : errorMessage;
        String lowerMessage = message.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (name.contains("freetiererror")) {
            return "OpenCode's free tier refused this request. Pick another free model, or add a provider "
                    + "with 'opencode auth login' in the AI CLI terminal tab.";
        }
        if (lowerMessage.contains("401") || lowerMessage.contains("unauthorized")
                || lowerMessage.contains("api key") || lowerMessage.contains("apikey")
                || lowerMessage.contains("not authenticated")) {
            return "OpenCode isn't signed in to that model's provider. Run 'opencode auth login' in the "
                    + "AI CLI terminal tab, or pick an opencode/ free model.";
        }
        if (name.contains("modelnotfound") || lowerMessage.contains("model not found")) {
            String modelId = model.isEmpty() ? DEFAULT_MODEL : model;
            return "OpenCode doesn't know the model '" + modelId
                    + "'. Pick one from the list or check 'opencode models'.";
        }
        List<String> discovered = discoveredModels;
        if ("unknownerror".equals(name)
                && model != null && !model.isBlank()
                && discovered != null && !discovered.isEmpty()
                && !discovered.contains(model)
                && !configuredModels().contains(model)) {
            return "OpenCode doesn't know the model '" + model
                    + "'. Pick one from the list or check 'opencode models'.";
        }
        if (!message.isBlank()) {
            return "OpenCode failed: " + firstLines(message);
        }
        return describeFailure(result);
    }

    private static String describeFailure(CliProcessResult result) {
        String detail = result.getStderr() == null ? "" : result.getStderr().trim();
        if (detail.isEmpty() && result.getStdout() != null) {
            detail = result.getStdout().trim();
        }
        String suffix = detail.isEmpty() ? "" : " Details: " + firstLines(detail);
        return "OpenCode failed: exited with code " + result.getExitCode() + "." + suffix;
    }

    private static String firstLines(String text) {
        String[] lines = text.trim().split("\\R");
        int count = Math.min(lines.length, 3);
        return String.join(" ", java.util.Arrays.copyOfRange(lines, 0, count));
    }

    private static ParsedOutput parseOutput(String stdout) {
        StringBuilder answer = new StringBuilder();
        String errorName = null;
        String errorMessage = null;
        if (stdout == null || stdout.isEmpty()) {
            return new ParsedOutput("", null, null);
        }
        for (String line : stdout.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode event;
            try {
                event = JSON.readTree(line);
            } catch (JacksonException e) {
                continue;
            }
            if (event == null || !event.isObject()) {
                continue;
            }
            String type = event.path("type").asText("");
            if ("step_start".equals(type)) {
                answer.setLength(0);
            } else if ("text".equals(type)) {
                JsonNode text = event.path("part").path("text");
                if (text.isTextual()) {
                    answer.append(text.asText());
                }
            } else if ("error".equals(type)) {
                JsonNode error = event.path("error");
                if (!error.isObject()) {
                    error = event;
                }
                errorName = firstNonBlank(error.path("name").asText(null),
                        event.path("name").asText(null));
                JsonNode data = error.path("data");
                errorMessage = firstNonBlank(data.path("message").asText(null),
                        error.path("message").asText(null),
                        event.path("data").path("message").asText(null));
            }
        }
        return new ParsedOutput(answer.toString(), errorName, errorMessage);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Duration executionTimeout() {
        return Duration.ofSeconds(parsePositiveLong(
                AiConfig.getProperty(TIMEOUT_KEY, "180"), 180L));
    }

    private static long parsePositiveLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("feather-wand-opencode");
        } catch (IOException e) {
            throw new CliProviderException("Could not create a temporary directory for OpenCode.", e);
        }
    }

    private static void deleteTreeQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Could not remove the temporary OpenCode directory: {}", e.getMessage());
        }
    }

    @Override
    public List<String> listModels() {
        List<String> configured = configuredModels();
        List<String> discovered = discoveredModels;
        if (discovered == null && !SwingUtilities.isEventDispatchThread()) {
            String exe = executable();
            if (exe != null) {
                discovered = discoverModels(exe);
            }
        }
        Set<String> models = new LinkedHashSet<>();
        models.add(DEFAULT_MODEL);
        models.addAll(configured);
        if (discovered != null) {
            models.addAll(discovered);
        }
        return List.copyOf(models);
    }

    public List<String> featuredModelIds() {
        if (!isEnabled()) {
            return List.of();
        }
        Set<String> featured = new LinkedHashSet<>();
        featured.add(MODEL_PREFIX + DEFAULT_MODEL);
        for (String modelId : configuredModels()) {
            featured.add(MODEL_PREFIX + modelId);
        }
        return List.copyOf(featured);
    }

    private List<String> configuredModels() {
        Set<String> models = new LinkedHashSet<>();
        String configured = AiConfig.getProperty(MODELS_KEY, "");
        if (configured != null) {
            for (String value : configured.split(",")) {
                String id = value.trim();
                if (!id.isEmpty()) {
                    models.add(id);
                }
            }
        }
        return List.copyOf(models);
    }

    private List<String> discoverModels(String exe) {
        List<String> cached = discoveredModels;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = discoveredModels;
            if (cached != null) {
                return cached;
            }
            CliProcessResult result;
            try {
                result = runner.run(List.of(exe, "models"), null, MODELS_TIMEOUT);
            } catch (RuntimeException e) {
                log.debug("OpenCode model discovery failed: {}", e.getMessage());
                return List.of();
            }
            if (!result.isSuccess()) {
                return List.of();
            }
            List<String> models = new ArrayList<>();
            for (String line : result.getStdout().split("\\R")) {
                String id = line.trim();
                if (MODEL_ID.matcher(id).matches() && !models.contains(id)) {
                    models.add(id);
                }
            }
            discoveredModels = List.copyOf(models);
            return discoveredModels;
        }
    }

    private static final class ParsedOutput {
        private final String answer;
        private final String errorName;
        private final String errorMessage;

        ParsedOutput(String answer, String errorName, String errorMessage) {
            this.answer = answer;
            this.errorName = errorName;
            this.errorMessage = errorMessage;
        }
    }
}
