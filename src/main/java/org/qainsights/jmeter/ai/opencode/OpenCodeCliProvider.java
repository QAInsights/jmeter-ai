package org.qainsights.jmeter.ai.opencode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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

/**
 * Runs the local OpenCode CLI for Chat and Agent Mode. Requests use a fresh,
 * empty working directory and a restrictive permission configuration; OpenCode
 * keeps ownership of provider authentication.
 */
public final class OpenCodeCliProvider implements SubscriptionCliProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeCliProvider.class);
    private static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9._-]+/\\S+$");
    private static final Pattern VERSION_MAJOR = Pattern.compile("(?:^|[^0-9.])v?(\\d+)\\.\\d+");
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
    private final String executableOverride;
    private final boolean skipConfiguredExecutable;
    private String executable;
    private boolean detectionAttempted;
    private volatile int detectedMajorVersion;
    private volatile List<String> discoveredModels;
    private volatile String model = "";

    public OpenCodeCliProvider() {
        this(new OpenCodeCliAdapter(), new DefaultCliProcessRunner(), null, false);
    }

    OpenCodeCliProvider(OpenCodeCliAdapter adapter, CliProcessRunner runner) {
        this(adapter, runner, null, false);
    }

    private OpenCodeCliProvider(OpenCodeCliAdapter adapter, CliProcessRunner runner,
            String executableOverride, boolean skipConfiguredExecutable) {
        this.adapter = adapter;
        this.runner = runner;
        this.executableOverride = executableOverride;
        this.skipConfiguredExecutable = skipConfiguredExecutable;
    }

    public static OpenCodeCliProvider forExecutable(String path) {
        return forExecutable(path, new OpenCodeCliAdapter(), new DefaultCliProcessRunner());
    }

    static OpenCodeCliProvider forExecutable(String path, OpenCodeCliAdapter adapter, CliProcessRunner runner) {
        String override = path == null || path.isBlank() ? null : path.trim();
        return new OpenCodeCliProvider(adapter, runner, override, override == null);
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

    public synchronized String executable() {
        if (executableOverride != null) {
            return executableOverride;
        }
        if (!skipConfiguredExecutable) {
            String override = AiConfig.getProperty(EXECUTABLE_KEY, "");
            if (override != null && !override.trim().isEmpty()) {
                return override.trim();
            }
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
            detectedMajorVersion = 0;
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
            if (!result.isSuccess()) {
                return OpenCodeAuthStatus.UNKNOWN;
            }
            int major = parseMajorVersion(
                    OpenCodeOutputParser.firstNonBlank(result.getStdout(), result.getStderr()));
            if (major > 0) {
                detectedMajorVersion = major;
            }
            return OpenCodeAuthStatus.READY;
        } catch (RuntimeException e) {
            log.warn("Could not read the OpenCode CLI status: {}", e.getMessage());
            return OpenCodeAuthStatus.UNKNOWN;
        }
    }

    private static int parseMajorVersion(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return 0;
        }
        Matcher matcher = VERSION_MAJOR.matcher(versionOutput);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
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
            Duration timeout = executionTimeout();
            CliProcessResult result = runWithFallback(exe, tempDirectory, requestModel, prompt, timeout);
            return OpenCodeOutputParser.readAnswer(
                    result, timeout, TIMEOUT_KEY, requestModel, discoveredModels, configuredModels());
        } finally {
            deleteTreeQuietly(tempDirectory);
        }
    }

    private CliProcessResult runWithFallback(
            String exe, Path directory, String model, String prompt, Duration timeout) {
        boolean useLegacyV1 = detectedMajorVersion == 1;
        Map<String, String> env = Map.of("OPENCODE_CONFIG_CONTENT", SAFE_CONFIG);
        CliProcessResult result = runner.run(
                buildRunCommand(exe, directory, model, useLegacyV1),
                prompt, timeout, directory, env);
        if (useLegacyV1 && isUnsupportedV1FlagError(result)) {
            detectedMajorVersion = 2;
            return runner.run(
                    buildRunCommand(exe, directory, model, false),
                    prompt, timeout, directory, env);
        }
        return result;
    }

    private static boolean isUnsupportedV1FlagError(CliProcessResult result) {
        if (result.isSuccess() || result.isTimedOut()) {
            return false;
        }
        String stdout = result.getStdout() == null ? "" : result.getStdout();
        String stderr = result.getStderr() == null ? "" : result.getStderr();
        String combined = (stdout + '\n' + stderr).toLowerCase(Locale.ROOT);
        boolean unknownFlag = combined.contains("unrecognized flag")
                || combined.contains("unknown flag")
                || combined.contains("unknown option");
        return unknownFlag && (combined.contains("--pure") || combined.contains("--dir"));
    }

    List<String> buildRunCommand(String exe, Path directory, String model) {
        return buildRunCommand(exe, directory, model, detectedMajorVersion == 1);
    }

    List<String> buildRunCommand(String exe, Path directory, String model, boolean legacyV1) {
        List<String> command = new ArrayList<>(List.of(exe, "run"));
        if (legacyV1) {
            command.add("--pure");
        }
        command.add("--format");
        command.add("json");
        if (legacyV1) {
            command.add("--dir");
            command.add(directory.toString());
        }
        if (!model.isEmpty()) {
            command.add("-m");
            command.add(model);
        }
        return command;
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
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
}
