package org.qainsights.jmeter.ai.mcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConfigTest {

    @Test
    void rendersCommandAndArgs() {
        String json = new McpServerConfig("jmeter", "uv")
                .args(Arrays.asList("--directory", "/srv/jmeter-mcp", "run", "jmeter_server.py"))
                .toJsonObject();

        assertTrue(json.contains("\"command\":\"uv\""), json);
        assertTrue(json.contains("\"args\":[\"--directory\",\"/srv/jmeter-mcp\",\"run\",\"jmeter_server.py\"]"), json);
        assertTrue(json.contains("\"disabled\":false"), json);
        // env / autoApprove omitted when empty
        assertFalse(json.contains("\"env\""), json);
        assertFalse(json.contains("\"autoApprove\""), json);
    }

    @Test
    void rendersEnvAutoApproveAndDisabled() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JMETER_HOME", "/opt/jmeter");
        String json = new McpServerConfig("jmeter", "uv")
                .args(Collections.singletonList("run"))
                .env(env)
                .autoApprove(Arrays.asList("execute_jmeter_test", "analyze_jtl"))
                .disabled(true)
                .toJsonObject();

        assertTrue(json.contains("\"env\":{\"JMETER_HOME\":\"/opt/jmeter\"}"), json);
        assertTrue(json.contains("\"autoApprove\":[\"execute_jmeter_test\",\"analyze_jtl\"]"), json);
        assertTrue(json.contains("\"disabled\":true"), json);
    }

    @Test
    void escapesSpecialCharacters() {
        String json = new McpServerConfig("x", "C:\\tools\\uv.exe")
                .args(Collections.singletonList("a\"b"))
                .toJsonObject();
        assertTrue(json.contains("C:\\\\tools\\\\uv.exe"), json);
        assertTrue(json.contains("a\\\"b"), json);
    }
}
