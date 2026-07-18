package org.qainsights.jmeter.ai.record;

/**
 * States for the Feather Wand Record Mode session.
 */
public enum RecordingSessionState {
    OFF,
    ARMED,
    PREFLIGHT,
    PLANNING,
    EXECUTING,
    FLUSHING_HAR,
    CONVERTING,
    LOADING,
    AWAITING_CORRELATION,
    REVIEWING_CORRELATION,
    SAVING,
    DONE,
    FAILED,
    CANCELLED,
    CONVERT_PARTIAL
}
