package org.qainsights.jmeter.ai.telemetry;

/**
 * Thread-safe aggregate input/output token counts. Subtracting the exact
 * snapshot that was sent preserves usage recorded while the send was in flight.
 */
public class TelemetryTokenCounters {
    private long inputTokens;
    private long outputTokens;

    public synchronized void add(long input, long output) {
        if (input > 0) {
            inputTokens += Math.min(input, Long.MAX_VALUE - inputTokens);
        }
        if (output > 0) {
            outputTokens += Math.min(output, Long.MAX_VALUE - outputTokens);
        }
    }

    public synchronized void loadPending(long input, long output) {
        add(Math.max(0, input), Math.max(0, output));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(inputTokens, outputTokens);
    }

    public synchronized void subtract(Snapshot sent) {
        if (sent == null) {
            return;
        }
        inputTokens = Math.max(0, inputTokens - sent.inputTokens());
        outputTokens = Math.max(0, outputTokens - sent.outputTokens());
    }

    public record Snapshot(long inputTokens, long outputTokens) { }
}
