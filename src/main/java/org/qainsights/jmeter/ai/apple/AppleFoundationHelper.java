package org.qainsights.jmeter.ai.apple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.DefaultCliProcessRunner;
import org.qainsights.jmeter.ai.telemetry.TelemetryClient;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class AppleFoundationHelper {
    public static final String PATH_PROPERTY = "apple.fm.helper.path";
    private static final Logger log = LoggerFactory.getLogger(AppleFoundationHelper.class);
    private static final String RESOURCE =
            "/org/qainsights/jmeter/ai/native/macos-arm64/feather-wand-afm";
    private static final long STATUS_CACHE_TTL_NANOS = Duration.ofSeconds(60).toNanos();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile CachedStatus cachedStatus;

    private AppleFoundationHelper() {
    }

    public static Optional<Path> resolveExecutable() {
        return resolveExecutable(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                AiConfig.getProperty(PATH_PROPERTY, ""),
                Path.of(System.getProperty("user.home", ".")));
    }

    static Optional<Path> resolveExecutable(String osName, String architecture,
                                            String configuredPath, Path userHome) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            try {
                return Optional.of(Path.of(configuredPath.trim()));
            } catch (RuntimeException e) {
                log.warn("Invalid Apple FoundationModels helper path: {}", e.getMessage());
                return Optional.empty();
            }
        }
        if (!isSupportedPlatform(osName, architecture)) {
            return Optional.empty();
        }
        try (InputStream in = AppleFoundationHelper.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.info("Apple FoundationModels helper resource is missing");
                return Optional.empty();
            }
            byte[] bytes = in.readAllBytes();
            String version = TelemetryClient.pluginVersion();
            String versionToken = version == null || version.isBlank()
                    || version.equals("unknown") || version.contains("${")
                    ? sha256(bytes) : version;
            Path executable = userHome.resolve(".jmeter-ai").resolve("bin")
                    .resolve("feather-wand-afm-" + versionToken);
            Files.createDirectories(executable.getParent());
            String resourceHash = sha256(bytes);
            if (!Files.isRegularFile(executable)
                    || !sha256(Files.readAllBytes(executable)).equals(resourceHash)) {
                Files.write(executable, bytes);
            }
            if (!executable.toFile().setExecutable(true, true) && !Files.isExecutable(executable)) {
                log.warn("Could not mark Apple FoundationModels helper executable: {}", executable);
                return Optional.empty();
            }
            return Optional.of(executable);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not install Apple FoundationModels helper: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static Status status() {
        CachedStatus cached = cachedStatus;
        if (cached != null && cached.isFresh()) {
            return cached.status;
        }
        synchronized (AppleFoundationHelper.class) {
            cached = cachedStatus;
            if (cached == null || !cached.isFresh()) {
                Status status = probeStatus(new DefaultCliProcessRunner(), resolveExecutable(),
                        isSupportedPlatform(System.getProperty("os.name", ""),
                                System.getProperty("os.arch", "")));
                cached = new CachedStatus(status, System.nanoTime());
                cachedStatus = cached;
            }
            return cached.status;
        }
    }

    static boolean isPermanent(Status status) {
        return status != null && (status.isAvailable()
                || "unsupportedPlatform".equals(status.getReason())
                || "deviceNotEligible".equals(status.getReason()));
    }

    static Status probeStatus(CliProcessRunner runner, Optional<Path> executable, boolean supportedPlatform) {
        if (!supportedPlatform) {
            return Status.unavailable("unsupportedPlatform");
        }
        if (executable.isEmpty()) {
            return Status.unavailable("unknown");
        }
        try {
            CliProcessResult result = runner.run(List.of(executable.get().toString(), "status"),
                    null, Duration.ofSeconds(5));
            return parseStatus(result.getStdout(), result.isSuccess());
        } catch (RuntimeException e) {
            log.info("Apple FoundationModels availability probe failed: {}", e.getMessage());
            return Status.unavailable("unknown");
        }
    }

    static Status parseStatus(String output, boolean successfulExit) {
        if (!successfulExit || output == null) {
            return Status.unavailable("unknown");
        }
        try {
            for (String line : output.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode json = MAPPER.readTree(line);
                if (json.path("available").asBoolean(false)) {
                    return Status.available();
                }
                String reason = json.path("reason").asText("unknown");
                return Status.unavailable(switch (reason) {
                    case "deviceNotEligible", "appleIntelligenceNotEnabled", "modelNotReady" -> reason;
                    default -> "unknown";
                });
            }
        } catch (Exception e) {
            log.debug("Could not parse Apple FoundationModels status: {}", e.getMessage());
        }
        return Status.unavailable("unknown");
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        return osName != null && osName.toLowerCase(java.util.Locale.ROOT).contains("mac")
                && architecture != null && architecture.equalsIgnoreCase("aarch64");
    }

    static void resetStatusCache() {
        cachedStatus = null;
    }

    private static final class CachedStatus {
        private final Status status;
        private final long probedAtNanos;

        private CachedStatus(Status status, long probedAtNanos) {
            this.status = status;
            this.probedAtNanos = probedAtNanos;
        }

        private boolean isFresh() {
            return isPermanent(status) || System.nanoTime() - probedAtNanos < STATUS_CACHE_TTL_NANOS;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static final class Status {
        private final boolean available;
        private final String reason;

        private Status(boolean available, String reason) {
            this.available = available;
            this.reason = reason;
        }

        static Status available() {
            return new Status(true, null);
        }

        static Status unavailable(String reason) {
            return new Status(false, reason);
        }

        public boolean isAvailable() {
            return available;
        }

        public String getReason() {
            return reason;
        }
    }
}
