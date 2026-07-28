package org.qainsights.jmeter.ai.mcp;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in smoke test against the real {@code @playwright/mcp} server.
 * <p>
 * Excluded from normal runs because it needs Node, network access, and an npx download.
 * Enable it with:
 * <pre>
 * mvn "-Dtest=PlaywrightMcpIntegrationTest" "-Dmcp.integration=true" "-Dsurefire.failIfNoSpecifiedTests=false" test
 * </pre>
 * This is the only place the plugin's assumptions meet the actual server, so it verifies
 * the handshake, the tool catalogue, and - most importantly - that the real Playwright
 * schemas survive translation into {@link ToolSpec}.
 */
@EnabledIfSystemProperty(named = "mcp.integration", matches = "true")
class PlaywrightMcpIntegrationTest {

    /** npx may download the package on first run, so allow generous time. */
    private static final long TIMEOUT_MILLIS = 180_000L;

    @Test
    void should_handshakeAndAdvertiseBrowserTools(@TempDir Path tempDir) {
        assertTrue(PlaywrightMcpServerProcess.isNpxAvailable(),
                "npx must be on the PATH for this test");

        Path config = PlaywrightMcpConfigWriter.write(tempDir.resolve("playwright-mcp.json"),
                new PlaywrightMcpOptions(8888, true, List.of(), tempDir));

        try (PlaywrightMcpServerProcess server = PlaywrightMcpServerProcess.start(config);
             StdioMcpClient client = new StdioMcpClient(
                     server.rawProcess().getInputStream(),
                     server.rawProcess().getOutputStream(),
                     TIMEOUT_MILLIS)) {

            String protocol = client.initialize();
            System.out.println("Negotiated MCP protocol: " + protocol);

            List<McpTool> tools = client.listTools();
            System.out.println("Playwright MCP advertises " + tools.size() + " tools:");
            tools.forEach(t -> System.out.println("  - " + t.name()));

            assertFalse(tools.isEmpty());
            assertTrue(tools.stream().anyMatch(t -> "browser_navigate".equals(t.name())),
                    "browser_navigate is the tool the recording workflow depends on most");
            assertTrue(tools.stream().anyMatch(t -> "browser_snapshot".equals(t.name())),
                    "the observe step of the observe-act-observe loop needs snapshots");
        }
    }

    @Test
    void should_translateEveryRealSchemaWithoutLosingRequiredArguments(@TempDir Path tempDir) {
        Path config = PlaywrightMcpConfigWriter.write(tempDir.resolve("playwright-mcp.json"),
                new PlaywrightMcpOptions(8888, true, List.of(), tempDir));

        try (PlaywrightMcpServerProcess server = PlaywrightMcpServerProcess.start(config);
             StdioMcpClient client = new StdioMcpClient(
                     server.rawProcess().getInputStream(),
                     server.rawProcess().getOutputStream(),
                     TIMEOUT_MILLIS)) {

            client.initialize();

            for (McpTool tool : client.listTools()) {
                ToolSpec spec = McpSchemaTranslator.toToolSpec(tool);

                assertEquals(tool.name(), spec.getName());
                int declared = tool.inputSchema().path("properties").size();
                assertEquals(declared, spec.getParameters().size(),
                        "parameters were dropped translating " + tool.name());

                int requiredInSchema = tool.inputSchema().path("required").size();
                assertEquals(requiredInSchema, spec.getRequiredParameters().size(),
                        "required flags were lost translating " + tool.name());
            }
        }
    }
}
