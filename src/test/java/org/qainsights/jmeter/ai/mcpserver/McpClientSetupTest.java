package org.qainsights.jmeter.ai.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpClientSetupTest {

    private static final String URL = "http://127.0.0.1:7397/mcp";
    private static final String TOKEN = "f".repeat(64);

    @Test
    void claudeCodeCommand() {
        assertEquals("claude mcp add --transport http feather-wand " + URL
                + " --header \"Authorization: Bearer " + TOKEN + "\"", McpClientSetup.claudeCode(URL, TOKEN));
    }

    @Test
    void cursorJsonIsValid() throws Exception {
        JsonNode server = new ObjectMapper().readTree(McpClientSetup.cursor(URL, TOKEN))
                .path("mcpServers").path("feather-wand");
        assertEquals(URL, server.path("url").asText());
        assertEquals("Bearer " + TOKEN, server.path("headers").path("Authorization").asText());
    }

    @Test
    void codexToml() {
        assertEquals("[mcp_servers.feather-wand]\nurl = \"" + URL + "\"\n"
                + "http_headers = { \"Authorization\" = \"Bearer " + TOKEN + "\" }",
                McpClientSetup.codex(URL, TOKEN));
    }
}
