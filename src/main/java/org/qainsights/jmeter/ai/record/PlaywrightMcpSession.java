package org.qainsights.jmeter.ai.record;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.mcp.McpException;
import org.qainsights.jmeter.ai.mcp.McpToolBridge;
import org.qainsights.jmeter.ai.mcp.PlaywrightMcpConfigWriter;
import org.qainsights.jmeter.ai.mcp.PlaywrightMcpOptions;
import org.qainsights.jmeter.ai.mcp.PlaywrightMcpServerProcess;
import org.qainsights.jmeter.ai.mcp.PlaywrightTools;
import org.qainsights.jmeter.ai.mcp.StdioMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BrowserSession} backed by a Playwright MCP child process, with the browser
 * routed through the JMeter recording proxy.
 * <p>
 * Only the tools in {@link PlaywrightTools#RECORDING_ALLOW_LIST} are exposed, so
 * code-execution tools such as {@code browser_run_code_unsafe} are unreachable by
 * construction rather than by asking the model not to use them.
 */
public final class PlaywrightMcpSession implements BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightMcpSession.class);

    /** Generous: the first run may download the package before the handshake completes. */
    private static final long STARTUP_TIMEOUT_MILLIS = 180_000L;

    private final PlaywrightMcpServerProcess process;
    private final StdioMcpClient client;
    private final List<Tool> tools;

    private PlaywrightMcpSession(PlaywrightMcpServerProcess process, StdioMcpClient client,
                                 List<Tool> tools) {
        this.process = process;
        this.client = client;
        this.tools = tools;
    }

    /**
     * Launches the browser and returns the tools that drive it.
     *
     * @param proxyPort      the recording proxy port the browser must use
     * @param artifactDir    where the config file and server artifacts are written
     * @param allowedOrigins origins the browser may reach; empty means unrestricted
     * @throws RecordingException if the browser could not be started
     */
    public static PlaywrightMcpSession open(int proxyPort, Path artifactDir,
                                            List<String> allowedOrigins) {
        if (!PlaywrightMcpServerProcess.isNpxAvailable()) {
            throw new RecordingException("Record Mode drives the browser with Playwright MCP, "
                    + "which needs Node.js. Install Node.js (which provides npx) and try again.");
        }
        Path configFile = artifactDir.resolve("playwright-mcp.json");
        PlaywrightMcpConfigWriter.write(configFile,
                new PlaywrightMcpOptions(proxyPort, false, allowedOrigins, artifactDir));

        PlaywrightMcpServerProcess process = null;
        StdioMcpClient client = null;
        try {
            process = PlaywrightMcpServerProcess.start(configFile);
            client = process.newClient(STARTUP_TIMEOUT_MILLIS);
            client.initialize();

            List<Tool> tools = new ArrayList<>();
            for (Tool tool : new McpToolBridge(client).tools()) {
                if (PlaywrightTools.RECORDING_ALLOW_LIST.contains(tool.getSpec().getName())) {
                    tools.add(tool);
                }
            }
            if (tools.isEmpty()) {
                throw new RecordingException("The browser automation server started but offered "
                        + "none of the expected tools, so the recording cannot proceed.");
            }
            log.info("Playwright MCP ready with {} browser tools", tools.size());
            return new PlaywrightMcpSession(process, client, tools);
        } catch (McpException e) {
            closeQuietly(client, process);
            throw new RecordingException("Could not start the browser: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            closeQuietly(client, process);
            throw e;
        }
    }

    /** The production factory. */
    public static BrowserSession.Factory factory() {
        return PlaywrightMcpSession::open;
    }

    @Override
    public List<Tool> tools() {
        return tools;
    }

    @Override
    public void close() {
        closeQuietly(client, process);
    }

    /**
     * Closes the client before the process: the client's stream close lets the server see
     * EOF on stdin and exit on its own, which is cleaner than killing it outright.
     */
    private static void closeQuietly(StdioMcpClient client, PlaywrightMcpServerProcess process) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("Error closing the MCP client", e);
            }
        }
        if (process != null) {
            try {
                process.close();
            } catch (RuntimeException e) {
                log.debug("Error stopping the MCP server process", e);
            }
        }
    }
}
