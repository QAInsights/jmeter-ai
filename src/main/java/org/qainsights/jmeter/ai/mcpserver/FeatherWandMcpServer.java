package org.qainsights.jmeter.ai.mcpserver;

import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.agent.jmeter.SwingToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryClient;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Starts and stops the one MCP server that runs inside the JMeter GUI. */
public final class FeatherWandMcpServer {

    private static final Logger log = LoggerFactory.getLogger(FeatherWandMcpServer.class);

    public static final String ENABLED_KEY = "jmeter.ai.mcp.server.enabled";
    public static final String PORT_KEY = "jmeter.ai.mcp.server.port";
    public static final int DEFAULT_PORT = 7397;

    private static McpHttpServer running;

    private FeatherWandMcpServer() {
    }

    /** Starts the server at JMeter start-up when {@value #ENABLED_KEY} is true. Never throws. */
    public static void startIfEnabled() {
        if (!Boolean.parseBoolean(AiConfig.getProperty(ENABLED_KEY, "false"))) {
            return;
        }
        try {
            start();
        } catch (IOException | RuntimeException e) {
            log.warn("Feather Wand MCP server could not start on port {}: {}", configuredPort(), e.toString());
        }
    }

    /** Starts the server if it is not running and returns its URL. */
    public static synchronized String start() throws IOException {
        if (running == null) {
            String token = McpServerToken.loadOrCreate(McpServerToken.defaultPath());
            McpServerProtocol protocol = new McpServerProtocol(AgentToolRegistry.createDefault(),
                    JMeterAgent.destructiveTools(), destructiveGate(), TelemetryClient.pluginVersion());
            running = McpHttpServer.start(configuredPort(), token, protocol);
            Telemetry.record(TelemetryFeature.MCP_SERVER_START);
            log.info("Feather Wand MCP server listening on {}", url(running.port()));
        }
        return url(running.port());
    }

    public static synchronized void stop() {
        if (running != null) {
            running.close();
            running = null;
            log.info("Feather Wand MCP server stopped");
        }
    }

    public static synchronized boolean isRunning() {
        return running != null;
    }

    /** The URL clients connect to: the live port when running, otherwise the configured one. */
    public static synchronized String url() {
        return url(running != null ? running.port() : configuredPort());
    }

    /** The token clients must send: the live server's when running, otherwise the stored one. */
    public static synchronized String token() throws IOException {
        return running != null ? running.token() : McpServerToken.loadOrCreate(McpServerToken.defaultPath());
    }

    /** False when {@code jmeter.ai.agent.confirm.destructive=false} lets destructive tools run unasked. */
    public static boolean confirmsDestructive() {
        return Boolean.parseBoolean(AiConfig.getProperty(JMeterAgent.CONFIRM_DESTRUCTIVE_KEY, "true"));
    }

    /**
     * Replaces the token so old client setups stop working. A running server switches to the new token
     * in place; if the new token cannot be written, the old one and the listener stay as they were.
     */
    public static synchronized String regenerateToken() throws IOException {
        String token = McpServerToken.regenerate(McpServerToken.defaultPath());
        if (running != null) {
            running.setToken(token);
        }
        return token;
    }

    static int configuredPort() {
        try {
            int port = Integer.parseInt(AiConfig.getProperty(PORT_KEY, String.valueOf(DEFAULT_PORT)).trim());
            return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    static String url(int port) {
        return "http://127.0.0.1:" + port + McpHttpServer.PATH;
    }

    private static ToolConfirmationGate destructiveGate() {
        return confirmsDestructive() ? new SwingToolConfirmationGate() : null;
    }
}
