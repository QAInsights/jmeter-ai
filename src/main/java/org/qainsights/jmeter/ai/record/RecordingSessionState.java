package org.qainsights.jmeter.ai.record;

/**
 * States for the Feather Wand Record Mode session.
 * <p>
 * The former {@code FLUSHING_HAR}, {@code CONVERTING}, {@code LOADING} and
 * {@code CONVERT_PARTIAL} states belonged to the abandoned HAR-conversion design. With
 * JMeter's {@code ProxyControl} writing real samplers into the tree as traffic arrives,
 * there is no capture file to flush, convert or load: the plan is already built when
 * {@code EXECUTING} ends, so the session goes straight to correlation.
 */
public enum RecordingSessionState {
    OFF,
    ARMED,
    PREFLIGHT,
    PLANNING,
    EXECUTING,
    AWAITING_CORRELATION,
    REVIEWING_CORRELATION,
    SAVING,
    DONE,
    FAILED,
    CANCELLED
}
