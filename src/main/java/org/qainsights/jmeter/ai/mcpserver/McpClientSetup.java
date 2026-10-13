package org.qainsights.jmeter.ai.mcpserver;

/** Ready-to-paste setup for the MCP clients Feather Wand documents. */
public final class McpClientSetup {

    public static final String SERVER_KEY = "feather-wand";

    private McpClientSetup() {
    }

    /** Claude Code: one command, run in a terminal. */
    public static String claudeCode(String url, String token) {
        return "claude mcp add --transport http " + SERVER_KEY + " " + url
                + " --header \"Authorization: Bearer " + token + "\"";
    }

    /** Cursor: an entry for {@code ~/.cursor/mcp.json} or a project's {@code .cursor/mcp.json}. */
    public static String cursor(String url, String token) {
        return "{\n"
                + "  \"mcpServers\": {\n"
                + "    \"" + SERVER_KEY + "\": {\n"
                + "      \"url\": \"" + url + "\",\n"
                + "      \"headers\": { \"Authorization\": \"Bearer " + token + "\" }\n"
                + "    }\n"
                + "  }\n"
                + "}";
    }

    /** Codex: a block for {@code ~/.codex/config.toml}. */
    public static String codex(String url, String token) {
        return "[mcp_servers." + SERVER_KEY + "]\n"
                + "url = \"" + url + "\"\n"
                + "http_headers = { \"Authorization\" = \"Bearer " + token + "\" }";
    }
}
