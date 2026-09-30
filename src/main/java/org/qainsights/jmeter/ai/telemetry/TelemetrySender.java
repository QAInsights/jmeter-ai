package org.qainsights.jmeter.ai.telemetry;

/** Sends one serialized ping. Implementations never throw. */
public interface TelemetrySender {
    /** @return true when the endpoint accepted the payload. */
    boolean send(String json);
}
