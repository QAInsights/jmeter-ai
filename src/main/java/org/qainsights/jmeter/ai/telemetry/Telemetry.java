package org.qainsights.jmeter.ai.telemetry;

import java.util.regex.Pattern;
import java.util.Locale;
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
                        + "(plugin/JMeter/Java version, OS, provider and well-known model name, feature and error counts). "
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
        String p = provider == null ? "" : provider.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return "err_" + (p.isEmpty() ? "unknown" : p.length() > 12 ? p.substring(0, 12) : p) + "_" + category;
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
