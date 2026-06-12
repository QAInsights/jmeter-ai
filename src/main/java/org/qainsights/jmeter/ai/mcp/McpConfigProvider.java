package org.qainsights.jmeter.ai.mcp;

import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the set of MCP servers to expose to the AI CLIs from JMeter properties.
 *
 * <p>The headline server is the <a href="https://github.com/QAInsights/jmeter-mcp-server">
 * JMeter MCP server</a>, which is run as {@code uv --directory &lt;dir&gt; run
 * jmeter_server.py}. It is only wired when an operator points at a local checkout
 * via {@code jmeter.ai.mcp.jmeter.dir}, so the default behaviour writes nothing.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code jmeter.ai.mcp.enabled} (default true)</li>
 *   <li>{@code jmeter.ai.mcp.jmeter.dir} — path to the jmeter-mcp-server checkout</li>
 *   <li>{@code jmeter.ai.mcp.jmeter.command} (default {@code uv})</li>
 *   <li>{@code jmeter.ai.mcp.jmeter.autoApprove} — comma list of tools to auto-approve</li>
 * </ul>
 */
public final class McpConfigProvider {

    public static final String ENABLED_PROP = "jmeter.ai.mcp.enabled";
    public static final String JMETER_DIR_PROP = "jmeter.ai.mcp.jmeter.dir";
    public static final String JMETER_CMD_PROP = "jmeter.ai.mcp.jmeter.command";
    public static final String JMETER_AUTO_APPROVE_PROP = "jmeter.ai.mcp.jmeter.autoApprove";

    private McpConfigProvider() {
    }

    public static boolean isEnabled() {
        return AiConfig.getProperty(ENABLED_PROP, "true").equalsIgnoreCase("true");
    }

    /** @return the servers to write, or an empty list when nothing is configured. */
    public static List<McpServerConfig> configuredServers() {
        List<McpServerConfig> servers = new ArrayList<>();
        if (!isEnabled()) {
            return servers;
        }

        String dir = AiConfig.getProperty(JMETER_DIR_PROP, "").trim();
        if (!dir.isEmpty()) {
            String command = AiConfig.getProperty(JMETER_CMD_PROP, "uv").trim();
            McpServerConfig jmeter = new McpServerConfig("jmeter", command)
                    .args(Arrays.asList("--directory", dir, "run", "jmeter_server.py"))
                    .autoApprove(splitCsv(AiConfig.getProperty(JMETER_AUTO_APPROVE_PROP, "")));
            servers.add(jmeter);
        }
        return servers;
    }

    static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}
