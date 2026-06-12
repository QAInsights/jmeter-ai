package org.qainsights.jmeter.ai.mcp;

import org.qainsights.jmeter.ai.claudecode.BaseCliAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a CLI-specific MCP configuration file into the working (test-plan)
 * directory so the launched agent can reach the configured MCP servers.
 *
 * <p>For Kiro this is {@code .kiro/settings/mcp.json}, in the standard
 * {@code {"mcpServers": { ... }}} schema. The file is project-scoped (lives next
 * to the {@code .jmx}), so it never disturbs the user's global config.
 */
public final class McpConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(McpConfigWriter.class);

    private McpConfigWriter() {
    }

    /**
     * Write MCP config for {@code adapter} into {@code workingDir}, if MCP is
     * enabled, the CLI supports MCP, and at least one server is configured.
     *
     * @return the path written, or {@code null} if nothing was written.
     */
    public static Path writeFor(BaseCliAdapter adapter, File workingDir) {
        if (adapter == null || workingDir == null || !adapter.supportsMcp()) {
            return null;
        }
        String relative = adapter.mcpConfigRelativePath();
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        List<McpServerConfig> servers = McpConfigProvider.configuredServers();
        if (servers.isEmpty()) {
            return null;
        }
        try {
            Path target = new File(workingDir, relative).toPath();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, render(servers).getBytes(StandardCharsets.UTF_8));
            log.info("Wrote MCP config for {} to {} ({} server(s))",
                    adapter.getName(), target, servers.size());
            return target;
        } catch (IOException e) {
            log.warn("Could not write MCP config for {}: {}", adapter.getName(), e.getMessage());
            return null;
        }
    }

    /** Render the full {@code {"mcpServers": {...}}} document. */
    public static String render(List<McpServerConfig> servers) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"mcpServers\": {\n");
        for (int i = 0; i < servers.size(); i++) {
            McpServerConfig s = servers.get(i);
            sb.append("    \"").append(s.name()).append("\": ").append(s.toJsonObject());
            sb.append(i < servers.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }
}
