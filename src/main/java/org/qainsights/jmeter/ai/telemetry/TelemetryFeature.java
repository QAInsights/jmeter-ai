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
    CMD_SLA("cmd_sla"),
    MCP_SERVER_START("mcp_server_start"),
    MCP_TOOL_CALL("mcp_tool_call"),
    CORRELATION_STUDIO_OPEN("correlation_studio_open"),
    AI_TERMINAL_TOGGLE("ai_terminal_toggle"),
    JSR223_REFACTOR("jsr223_refactor"),
    OUTCOME_COMPLETED("outcome_completed"),
    OUTCOME_STEP_LIMIT("outcome_step_limit"),
    OUTCOME_ERROR("outcome_error"),
    OUTCOME_FALLBACK("outcome_fallback"),
    OUTCOME_TOOL_FAILED("outcome_tool_failed"),
    OUTCOME_TOOL_DECLINED("outcome_tool_declined"),
    ROUTE_FOCUSED("route_focused"),
    ROUTE_ALL_TOOLS("route_all_tools"),
    ROUTE_UNAVAILABLE("route_unavailable"),
    ROUTE_EXPANDED("route_expanded"),
    TRIAGE_RUN("triage_run"),
    TRIAGE_UNCLASSIFIED("triage_unclassified"),
    EXTRACT_ADDED("extract_added"),
    OUTCOME_CANCELLED("outcome_cancelled"),
    AGENT_STEPS_1_5("agent_steps_1_5"),
    AGENT_STEPS_6_15("agent_steps_6_15"),
    AGENT_STEPS_16P("agent_steps_16p"),
    SETUP_OPEN("setup_open"),
    WELCOME_NO_PROVIDER("welcome_no_provider"),
    PICKER_OPEN("picker_open"),
    PICKER_SEARCH("picker_search"),
    PICKER_PIN("picker_pin"),
    PICKER_SELECT("picker_select"),
    PICKER_CUSTOM_ID("picker_custom_id"),
    REASONING_SET("reasoning_set"),
    ATTACH_FILE("attach_file"),
    ATTACH_LOG("attach_log"),
    ATTACH_RESULTS("attach_results"),
    ATTACH_DROP("attach_drop"),
    EXPORT_MD("export_md"),
    EXPORT_HTML("export_html"),
    UNDO("undo"),
    REDO("redo"),
    INTELLISENSE_PICK("intellisense_pick"),
    JSR223_TRYCATCH("jsr223_trycatch"),
    JSR223_FORMAT("jsr223_format"),
    EXTRACT_OPENED("extract_opened"),
    CORRELATION_RUN("correlation_run"),
    CORRELATION_APPLY("correlation_apply"),
    CLI_CLAUDECODE("cli_claudecode"),
    CLI_CODEX("cli_codex"),
    CLI_OPENCODE("cli_opencode"),
    CLI_COPILOT("cli_copilot"),
    CLI_ANTIGRAVITY("cli_antigravity"),
    CLI_GROK("cli_grok"),
    CLI_OTHER("cli_other"),
    MCP_SETUP_COPY("mcp_setup_copy"),
    DONATE_CLICK("donate_click");

    private final String wireName;

    TelemetryFeature(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
