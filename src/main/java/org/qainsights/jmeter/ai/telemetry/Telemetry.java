package org.qainsights.jmeter.ai.telemetry;

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

    private Telemetry() {
    }

    /** Idempotent: only the first call does anything. */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
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
                        + "(plugin/JMeter/Java version, OS, provider, feature usage counts). "
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
