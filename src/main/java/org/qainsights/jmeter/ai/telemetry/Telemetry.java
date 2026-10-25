package org.qainsights.jmeter.ai.telemetry;

import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Set;
import org.qainsights.jmeter.ai.utils.ProviderErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Static entry point for Feather Wand usage telemetry. Everything here is
 * best-effort and wrapped so nothing ever throws into JMeter.
 */
public final class Telemetry {
    private static final Logger log = LoggerFactory.getLogger(Telemetry.class);
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static volatile TelemetryCounters counters;
    private static volatile TelemetryState state;
    private static volatile String lastSelectedModel;
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Set<String> SETUP_OPTIONS = Set.of(
            "anthropic", "openai", "gemini", "deepseek", "grok", "metamuse",
            "openrouter", "opencode", "ollama", "smartrouting", "appleondevice");
    private static final Set<String> SETUP_OUTCOMES = Set.of(
            "ok", "auth_rejected", "rate_limited", "http_error", "network_error");
    private static final Set<String> DECISION_BACKENDS = Set.of("typesafe", "openai", "openrouter");
    private static final Set<String> DECISION_RESULTS = Set.of("ok", "fail", "slow");
    private static final Set<String> ERROR_CATEGORIES = Set.of(
            "auth", "forbidden", "not_found", "bad_request", "rate_limit",
            "server", "timeout", "unreachable", "other");
    /** Failure key for calls to a tool name that is not registered; the model's name is never sent. */
    static final String UNKNOWN_TOOL_FAIL_KEY = "toolfail__unknown";

    private Telemetry() {
    }

    /**
     * True inside a JUnit Platform test JVM (Maven, Gradle or an IDE). JMeter itself only ships JUnit 4,
     * so a real JMeter session never has the platform launcher on its classpath.
     */
    static boolean runningUnderTests() {
        try {
            Class.forName("org.junit.platform.launcher.core.LauncherFactory", false,
                    Telemetry.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Idempotent: only the first call does anything. */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        if (runningUnderTests()) {
            return;
        }
        try {
            TelemetryConfig config = new TelemetryConfig();
            if (!config.isEnabled()) {
                return;
            }
            TelemetryState loaded = TelemetryState.load();
            TelemetryCounters loadedCounters = new TelemetryCounters();
            loadedCounters.loadPending(loaded.getPendingFeatures());
            state = loaded;
            counters = loadedCounters;

            if (loaded.isNewInstall()) {
                log.info("Feather Wand sends an anonymous daily usage ping "
                        + "(plugin/JMeter/Java version, OS, provider and well-known model name, provider counts per message, "
                        + "setup test and save outcomes, Decisions call results, UI feature counts, Agent run-size buckets, "
                        + "cancellation and error counts; no prompts, keys or test plan content). "
                        + "Disable with jmeter.ai.telemetry.enabled=false or DO_NOT_TRACK=1.");
            }

            TelemetryClient client = new TelemetryClient(config, loaded, loadedCounters,
                    new HttpTelemetrySender(config.endpoint(), TelemetryClient.pluginVersion()));

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "feather-wand-telemetry");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(client::tick, 30, TimeUnit.HOURS.toSeconds(1), TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    TelemetryState s = state;
                    TelemetryCounters c = counters;
                    if (s != null && c != null) {
                        s.setPendingFeatures(c.snapshot());
                        s.save();
                    }
                } catch (Throwable t) {
                    // shutdown; nothing useful to do
                }
            }, "feather-wand-telemetry-shutdown"));
        } catch (Throwable t) {
            log.debug("Telemetry failed to start", t);
        }
    }

    /** Remembers the chat-panel model of the latest Chat or Agent message for the daily ping. */
    public static void noteModel(String selectedModel) {
        lastSelectedModel = selectedModel;
    }

    public static void recordChatProvider(String model) {
        recordKey("msg_" + TelemetryModels.providerKey(model));
    }

    public static void recordAgentProvider(String model) {
        recordKey("agentrun_" + TelemetryModels.providerKey(model));
    }

    public static void recordSetupTest(String option, String outcome) {
        String optionKey = setupOptionKey(option);
        String outcomeKey = setupOutcomeKey(outcome);
        if (optionKey != null && outcomeKey != null) {
            recordKey("setup_test_" + optionKey + "_" + outcomeKey);
        }
    }

    public static void recordSetupSave(String option) {
        String optionKey = setupOptionKey(option);
        if (optionKey != null) {
            recordKey("setup_save_" + optionKey);
        }
    }

    public static void recordDecisionResult(String backend, String result, String category) {
        String backendKey = backend == null ? "" : backend.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        String resultKey = result == null ? "" : result.toLowerCase(Locale.ROOT);
        if (!DECISION_BACKENDS.contains(backendKey) || !DECISION_RESULTS.contains(resultKey)) {
            return;
        }
        if ("fail".equals(resultKey)) {
            String categoryKey = category == null ? "" : category.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_]", "");
            if (!ERROR_CATEGORIES.contains(categoryKey)) {
                categoryKey = "other";
            }
            recordKey("decide_fail_" + backendKey + "_" + categoryKey);
        } else {
            recordKey("decide_" + resultKey + "_" + backendKey);
        }
    }

    public static void recordAgentSteps(int iterations) {
        if (iterations <= 5) {
            record(TelemetryFeature.AGENT_STEPS_1_5);
        } else if (iterations <= 15) {
            record(TelemetryFeature.AGENT_STEPS_6_15);
        } else {
            record(TelemetryFeature.AGENT_STEPS_16P);
        }
    }

    static String lastSelectedModel() {
        return lastSelectedModel;
    }

    /** Counts one call of an Agent tool by its built-in name, plus total and per-tool failure counters when it failed. */
    public static void recordTool(String toolName, boolean success) {
        recordKey(toolKey(toolName));
        if (!success) {
            record(TelemetryFeature.OUTCOME_TOOL_FAILED);
            recordKey(toolFailKey(toolName));
        }
    }

    /** Counts a call to a tool name that is not registered, without sending the name the model asked for. */
    public static void recordUnknownTool() {
        record(TelemetryFeature.OUTCOME_TOOL_FAILED);
        recordKey(UNKNOWN_TOOL_FAIL_KEY);
    }

    /** Counts a gated tool call the user declined; the tool never ran, so it is neither a call nor a failure. */
    public static void recordToolDeclined() {
        record(TelemetryFeature.OUTCOME_TOOL_DECLINED);
    }

    /** Swaps in the counters that record calls write to, returning the previous ones. Tests only. */
    static TelemetryCounters swapCountersForTesting(TelemetryCounters replacement) {
        TelemetryCounters previous = counters;
        counters = replacement;
        return previous;
    }

    /** Counts a provider failure as err_&lt;provider&gt;_&lt;category&gt;; never the message itself. */
    public static void recordError(Throwable error) {
        try {
            if (counters == null) {
                return;
            }
            recordKey(errorKey(TelemetryModels.provider(lastSelectedModel), ProviderErrors.category(error)));
        } catch (Throwable t) {
            // telemetry must never break the plugin
        }
    }

    static String toolKey(String toolName) {
        return prefixedToolKey("tool_", toolName);
    }

    static String toolFailKey(String toolName) {
        return prefixedToolKey("toolfail_", toolName);
    }

    private static String prefixedToolKey(String prefix, String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        int max = 40 - prefix.length();
        return prefix + (name.length() > max ? name.substring(0, max) : name);
    }

    static String errorKey(String provider, String category) {
        String categoryKey = category == null ? "" : category.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "");
        if (!ERROR_CATEGORIES.contains(categoryKey)) {
            categoryKey = "other";
        }
        return "err_" + TelemetryModels.normalizeProviderKey(provider) + "_" + categoryKey;
    }

    private static String setupOptionKey(String option) {
        String key = option == null ? "" : option.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SETUP_OPTIONS.contains(key) ? key : null;
    }

    private static String setupOutcomeKey(String outcome) {
        String key = outcome == null ? "" : outcome.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        return SETUP_OUTCOMES.contains(key) ? key : null;
    }

    private static void recordKey(String key) {
        try {
            TelemetryCounters c = counters;
            if (c != null && key != null && KEY.matcher(key).matches()) {
                c.increment(key);
            }
        } catch (Throwable t) {
            // telemetry must never break the plugin
        }
    }

    /** Cheap in-memory increment; never throws and does no IO. */
    public static void record(TelemetryFeature feature) {
        try {
            TelemetryCounters c = counters;
            if (c != null && feature != null) {
                c.increment(feature);
            }
        } catch (Throwable t) {
            // telemetry must never break the plugin
        }
    }
}
