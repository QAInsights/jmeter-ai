package org.qainsights.jmeter.ai.mcp;

import org.qainsights.jmeter.ai.security.AuditLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One MCP (Model Context Protocol) server entry, e.g. the JMeter MCP server that
 * lets an AI agent actually run tests and parse JTLs through tools instead of
 * free text. Rendered into the {@code mcpServers} object of a CLI's MCP config.
 */
public final class McpServerConfig {

    private final String name;
    private final String command;
    private final List<String> args = new ArrayList<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final List<String> autoApprove = new ArrayList<>();
    private boolean disabled;

    public McpServerConfig(String name, String command) {
        this.name = name;
        this.command = command;
    }

    public McpServerConfig args(List<String> a) {
        if (a != null) {
            this.args.addAll(a);
        }
        return this;
    }

    public McpServerConfig env(Map<String, String> e) {
        if (e != null) {
            this.env.putAll(e);
        }
        return this;
    }

    public McpServerConfig autoApprove(List<String> tools) {
        if (tools != null) {
            this.autoApprove.addAll(tools);
        }
        return this;
    }

    public McpServerConfig disabled(boolean d) {
        this.disabled = d;
        return this;
    }

    public String name() {
        return name;
    }

    /** Render this server as a JSON object body (the value under its name key). */
    public String toJsonObject() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"command\":\"").append(AuditLogger.escape(command)).append('"');
        sb.append(",\"args\":").append(jsonStringArray(args));
        if (!env.isEmpty()) {
            sb.append(",\"env\":").append(jsonStringMap(env));
        }
        if (!autoApprove.isEmpty()) {
            sb.append(",\"autoApprove\":").append(jsonStringArray(autoApprove));
        }
        sb.append(",\"disabled\":").append(disabled);
        sb.append('}');
        return sb.toString();
    }

    static String jsonStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(AuditLogger.escape(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    static String jsonStringMap(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(AuditLogger.escape(e.getKey())).append("\":\"")
              .append(AuditLogger.escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }
}
