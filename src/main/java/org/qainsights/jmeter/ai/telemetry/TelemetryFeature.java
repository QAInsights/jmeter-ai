package org.qainsights.jmeter.ai.telemetry;

/** Usage events counted for the anonymous daily telemetry ping. */
public enum TelemetryFeature {
    PANEL_OPEN("panel_open"),
    CHAT_MESSAGE("chat_message"),
    AGENT_RUN("agent_run"),
    RECORDING_RUN("recording_run"),
    CMD_THIS("cmd_this"),
    CMD_OPTIMIZE("cmd_optimize"),
    CMD_LINT("cmd_lint"),
    CMD_WRAP("cmd_wrap"),
    CMD_USAGE("cmd_usage"),
    CMD_TESTPLAN("cmd_testplan"),
    CORRELATION_STUDIO_OPEN("correlation_studio_open"),
    AI_TERMINAL_TOGGLE("ai_terminal_toggle"),
    JSR223_REFACTOR("jsr223_refactor");

    private final String wireName;

    TelemetryFeature(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
