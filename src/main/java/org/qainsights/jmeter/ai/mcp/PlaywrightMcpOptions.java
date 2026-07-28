package org.qainsights.jmeter.ai.mcp;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Settings for a Playwright MCP browser session.
 *
 * @param proxyPort       the port of the JMeter recording proxy the browser must route through
 * @param headless        whether to hide the browser window; a visible window lets the user
 *                        watch and intervene, so recording defaults to false
 * @param allowedOrigins  origins the browser may reach; empty means unrestricted. Acts as a
 *                        blast-radius limit on an agent that mis-navigates
 * @param outputDir       where the server writes its own artifacts (traces, screenshots)
 */
public record PlaywrightMcpOptions(
        int proxyPort,
        boolean headless,
        List<String> allowedOrigins,
        Path outputDir) {

    public PlaywrightMcpOptions {
        if (proxyPort <= 0 || proxyPort > 65535) {
            throw new IllegalArgumentException("proxyPort must be a valid port, got " + proxyPort);
        }
        allowedOrigins = allowedOrigins == null
                ? Collections.emptyList()
                : List.copyOf(allowedOrigins);
    }

    /** Visible browser, unrestricted origins, no artifact directory. */
    public static PlaywrightMcpOptions forProxy(int proxyPort) {
        return new PlaywrightMcpOptions(proxyPort, false, Collections.emptyList(), null);
    }
}
