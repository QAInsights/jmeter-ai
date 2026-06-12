package org.qainsights.jmeter.ai.headless;

import org.qainsights.jmeter.ai.security.AuditLogger;

import java.time.Instant;
import java.util.List;

/**
 * Renders the artifact a headless run leaves behind for CI to archive and for
 * humans to review in a pull request. Supports Markdown (default) and JSON.
 */
public final class HeadlessReport {

    public final String cli;
    public final String binary;
    public final String prompt;
    public final String jmx;
    public final List<String> command;
    public final int exitCode;
    public final boolean timedOut;
    public final String output;
    public final String timestamp;

    public HeadlessReport(String cli, String binary, String prompt, String jmx,
                          List<String> command, int exitCode, boolean timedOut, String output) {
        this.cli = nullToEmpty(cli);
        this.binary = nullToEmpty(binary);
        this.prompt = nullToEmpty(prompt);
        this.jmx = nullToEmpty(jmx);
        this.command = command;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.output = nullToEmpty(output);
        this.timestamp = Instant.now().toString();
    }

    public String status() {
        if (timedOut) {
            return "TIMED_OUT";
        }
        return exitCode == 0 ? "SUCCESS" : "FAILED";
    }

    public String render(String format) {
        return "json".equalsIgnoreCase(format) ? toJson() : toMarkdown();
    }

    private String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# JMeter AI Headless Report\n\n");
        sb.append("- **Status:** ").append(status()).append('\n');
        sb.append("- **Exit code:** ").append(exitCode).append('\n');
        sb.append("- **Timestamp:** ").append(timestamp).append('\n');
        sb.append("- **CLI:** ").append(cli).append('\n');
        sb.append("- **Binary:** ").append(binary).append('\n');
        if (!jmx.isEmpty()) {
            sb.append("- **Test plan:** ").append(jmx).append('\n');
        }
        sb.append("- **Command:** `").append(commandString()).append("`\n\n");
        sb.append("## Prompt\n\n").append(prompt).append("\n\n");
        sb.append("## Output\n\n```\n").append(output);
        if (!output.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("```\n");
        return sb.toString();
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        jsonField(sb, "status", status());
        sb.append(',');
        sb.append("\"exitCode\":").append(exitCode);
        sb.append(',');
        sb.append("\"timedOut\":").append(timedOut);
        sb.append(',');
        jsonField(sb, "timestamp", timestamp);
        sb.append(',');
        jsonField(sb, "cli", cli);
        sb.append(',');
        jsonField(sb, "binary", binary);
        sb.append(',');
        jsonField(sb, "jmx", jmx);
        sb.append(',');
        jsonField(sb, "command", commandString());
        sb.append(',');
        jsonField(sb, "prompt", prompt);
        sb.append(',');
        jsonField(sb, "output", output);
        sb.append('}');
        return sb.toString();
    }

    private String commandString() {
        return command == null ? "" : String.join(" ", command);
    }

    private static void jsonField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(AuditLogger.escape(value)).append('"');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
