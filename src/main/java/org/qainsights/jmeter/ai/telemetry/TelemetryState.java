package org.qainsights.jmeter.ai.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persisted telemetry state at {@code ~/.jmeter-ai/telemetry.json}. A missing or
 * corrupt file is regenerated with a fresh install id. Writes are atomic
 * (temp file + move).
 */
public class TelemetryState {
    private static final Logger log = LoggerFactory.getLogger(TelemetryState.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_NAME = "telemetry.json";

    private final Path file;
    private boolean newInstall;

    private String installId;
    private String lastPingDay;
    private boolean firstPingSent;
    private final AtomicBoolean firstChatMessageSent = new AtomicBoolean();
    private final AtomicBoolean firstAgentRunSent = new AtomicBoolean();
    private Map<String, Integer> pendingFeatures = new LinkedHashMap<>();
    private long pendingInputTokens;
    private long pendingOutputTokens;

    private TelemetryState(Path dir) {
        this.file = dir.resolve(FILE_NAME);
    }

    public static TelemetryState load() {
        return load(defaultDir());
    }

    /** Package-private seam for tests. */
    static TelemetryState load(Path dir) {
        TelemetryState state = new TelemetryState(dir);
        try {
            if (Files.isRegularFile(state.file)) {
                Stored stored = MAPPER.readValue(state.file.toFile(), Stored.class);
                if (stored.installId != null && !stored.installId.isBlank()) {
                    state.installId = stored.installId;
                    state.lastPingDay = stored.lastPingDay;
                    state.firstPingSent = stored.firstPingSent;
                    state.firstChatMessageSent.set(stored.firstChatMessageSent);
                    state.firstAgentRunSent.set(stored.firstAgentRunSent);
                    if (stored.pendingFeatures != null) {
                        state.pendingFeatures = new LinkedHashMap<>(stored.pendingFeatures);
                    }
                    state.pendingInputTokens = Math.max(0, stored.pendingInputTokens);
                    state.pendingOutputTokens = Math.max(0, stored.pendingOutputTokens);
                    return state;
                }
            }
        } catch (Exception e) {
            log.debug("Telemetry state file unreadable, regenerating", e);
        }
        state.installId = UUID.randomUUID().toString();
        state.newInstall = true;
        state.save();
        return state;
    }

    private static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".jmeter-ai");
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Stored stored = new Stored();
            stored.installId = installId;
            stored.lastPingDay = lastPingDay;
            stored.firstPingSent = firstPingSent;
            stored.firstChatMessageSent = firstChatMessageSent.get();
            stored.firstAgentRunSent = firstAgentRunSent.get();
            stored.pendingFeatures = pendingFeatures;
            stored.pendingInputTokens = pendingInputTokens;
            stored.pendingOutputTokens = pendingOutputTokens;
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            MAPPER.writeValue(tmp.toFile(), stored);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.debug("Failed to persist telemetry state", e);
        }
    }

    public String getInstallId() {
        return installId;
    }

    public String getLastPingDay() {
        return lastPingDay;
    }

    public void setLastPingDay(String lastPingDay) {
        this.lastPingDay = lastPingDay;
    }

    public boolean isFirstPingSent() {
        return firstPingSent;
    }

    public void setFirstPingSent(boolean firstPingSent) {
        this.firstPingSent = firstPingSent;
    }

    public Map<String, Integer> getPendingFeatures() {
        return new LinkedHashMap<>(pendingFeatures);
    }

    boolean recordFirstUse(TelemetryFeature feature) {
        if (feature == TelemetryFeature.CHAT_MESSAGE) {
            return firstChatMessageSent.compareAndSet(false, true);
        }
        if (feature == TelemetryFeature.AGENT_RUN) {
            return firstAgentRunSent.compareAndSet(false, true);
        }
        return false;
    }

    public void setPendingFeatures(Map<String, Integer> pendingFeatures) {
        this.pendingFeatures = pendingFeatures == null ? new LinkedHashMap<>() : new LinkedHashMap<>(pendingFeatures);
    }

    public long getPendingInputTokens() {
        return pendingInputTokens;
    }

    public long getPendingOutputTokens() {
        return pendingOutputTokens;
    }

    public void setPendingTokens(long inputTokens, long outputTokens) {
        this.pendingInputTokens = Math.max(0, inputTokens);
        this.pendingOutputTokens = Math.max(0, outputTokens);
    }

    /** True when a fresh install id was generated by this load. */
    public boolean isNewInstall() {
        return newInstall;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Stored {
        public String installId;
        public String lastPingDay;
        public boolean firstPingSent;
        public boolean firstChatMessageSent;
        public boolean firstAgentRunSent;
        public Map<String, Integer> pendingFeatures;
        public long pendingInputTokens;
        public long pendingOutputTokens;
    }
}
