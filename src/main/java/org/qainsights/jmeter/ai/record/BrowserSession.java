package org.qainsights.jmeter.ai.record;

import java.nio.file.Path;
import java.util.List;
import org.qainsights.jmeter.ai.agent.tool.Tool;

/**
 * An open browser the agent can drive, expressed purely as a set of agent tools.
 * <p>
 * This is the seam that keeps {@link RecordingWorkflowService} testable: the production
 * implementation launches Playwright MCP over a child process, while tests supply stub
 * tools and assert the orchestration without Node, a browser, or a network.
 */
public interface BrowserSession extends AutoCloseable {

    /** The browser-driving tools to advertise to the model. */
    List<Tool> tools();

    /** Shuts the browser down. Must not throw. */
    @Override
    void close();

    /** Opens a browser routed through the recording proxy. */
    @FunctionalInterface
    interface Factory {
        /**
         * @param proxyPort      the recording proxy the browser must route through
         * @param artifactDir    where session files may be written
         * @param allowedOrigins origins the browser may reach; empty means unrestricted
         * @return the open session
         * @throws RecordingException if the browser could not be started
         */
        BrowserSession open(int proxyPort, Path artifactDir, List<String> allowedOrigins);
    }
}
