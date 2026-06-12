package org.qainsights.jmeter.ai.security;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * Append-only audit trail for AI CLI invocations.
 *
 * <p>Enterprises need to answer "who sent what to which AI, and when" for
 * security reviews and compliance. Every time the embedded terminal launches an
 * AI CLI, one JSON line is appended to the audit log capturing the actor, the
 * CLI/binary, the resolved command (flags only — no secrets), the working
 * directory, whether redaction was active, and a SHA-256 of the redacted context
 * that was shared. The hash lets auditors prove exactly what was sent without
 * the log itself ever storing the (potentially sensitive) content.
 *
 * <p>Enabled by default; controlled by {@code jmeter.ai.security.audit.enabled}.
 * The destination is {@code jmeter.ai.security.audit.file}, defaulting to
 * {@code <JMETER_HOME>/logs/jmeter-ai-audit.log} (or {@code ~/.jmeter-ai-audit.log}).
 */
public final class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private static final String ENABLED_PROP = "jmeter.ai.security.audit.enabled";
    private static final String FILE_PROP = "jmeter.ai.security.audit.file";

    private AuditLogger() {
    }

    public static boolean isEnabled() {
        return AiConfig.getProperty(ENABLED_PROP, "true").equalsIgnoreCase("true");
    }

    /**
     * Record one AI CLI launch. Never throws — auditing must not break the
     * terminal; failures are logged via slf4j instead.
     *
     * @param cliName       display name of the CLI (e.g. "AWS Kiro")
     * @param binaryPath    resolved binary path
     * @param command       full argv (flags only; no secret values)
     * @param workingDir    working directory shared with the CLI (may be null)
     * @param sharedContext the redacted context text written for the CLI (may be null)
     */
    public static void recordLaunch(String cliName, String binaryPath, List<String> command,
                                    String workingDir, String sharedContext) {
        if (!isEnabled()) {
            return;
        }
        try {
            StringBuilder json = new StringBuilder(512);
            json.append('{');
            field(json, "timestamp", Instant.now().toString());
            json.append(',');
            field(json, "event", "ai_cli_launch");
            json.append(',');
            field(json, "user", System.getProperty("user.name", "unknown"));
            json.append(',');
            field(json, "host", hostName());
            json.append(',');
            field(json, "cli", cliName);
            json.append(',');
            field(json, "binary", binaryPath);
            json.append(',');
            field(json, "command", command == null ? "" : String.join(" ", command));
            json.append(',');
            field(json, "workingDir", workingDir);
            json.append(',');
            field(json, "redactionEnabled", String.valueOf(SecretRedactor.isEnabled()));
            json.append(',');
            field(json, "contextSha256", sha256(sharedContext));
            json.append(',');
            field(json, "contextBytes",
                    String.valueOf(sharedContext == null ? 0 : sharedContext.getBytes(StandardCharsets.UTF_8).length));
            json.append('}');

            String line = json.toString();
            log.info("AI audit: {}", line);
            Path target = resolveAuditFile();
            Files.write(target, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // Auditing is best-effort; never disrupt the user's session.
            log.warn("Could not write AI audit entry: {}", e.getMessage());
        }
    }

    private static Path resolveAuditFile() throws IOException {
        String configured = AiConfig.getProperty(FILE_PROP, "").trim();
        if (!configured.isEmpty()) {
            Path p = Paths.get(expandHome(configured));
            ensureParent(p);
            return p;
        }
        String jmeterHome = System.getProperty("jmeter.home",
                System.getenv().getOrDefault("JMETER_HOME", ""));
        if (jmeterHome != null && !jmeterHome.trim().isEmpty()) {
            Path logsDir = Paths.get(jmeterHome, "logs");
            try {
                Files.createDirectories(logsDir);
                return logsDir.resolve("jmeter-ai-audit.log");
            } catch (IOException ignored) {
                // fall through to home directory
            }
        }
        return Paths.get(System.getProperty("user.home", "."), ".jmeter-ai-audit.log");
    }

    private static void ensureParent(Path p) throws IOException {
        Path parent = p.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    static String sha256(String text) {
        if (text == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home", "") + path.substring(1);
        }
        return path;
    }

    private static void field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    /** Minimal JSON string escaping (dependency-free). */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
