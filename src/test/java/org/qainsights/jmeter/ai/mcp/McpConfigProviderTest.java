package org.qainsights.jmeter.ai.mcp;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigProviderTest {

    @BeforeAll
    static void initProps() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File tmp = File.createTempFile("jmeter-test", ".properties");
            tmp.deleteOnExit();
            JMeterUtils.loadJMeterProperties(tmp.getAbsolutePath());
        }
    }

    @Test
    void noJmeterDir_yieldsNoServers() {
        // dir unset by default -> nothing to wire
        assertTrue(McpConfigProvider.configuredServers().isEmpty());
    }

    @Test
    void withJmeterDir_buildsJmeterServer() {
        withProperty(McpConfigProvider.JMETER_DIR_PROP, "/srv/jmeter-mcp", () ->
                withProperty(McpConfigProvider.JMETER_AUTO_APPROVE_PROP, "execute_jmeter_test", () -> {
                    List<McpServerConfig> servers = McpConfigProvider.configuredServers();
                    assertEquals(1, servers.size());
                    String json = servers.get(0).toJsonObject();
                    assertEquals("jmeter", servers.get(0).name());
                    assertTrue(json.contains("\"command\":\"uv\""), json);
                    assertTrue(json.contains("/srv/jmeter-mcp"), json);
                    assertTrue(json.contains("jmeter_server.py"), json);
                    assertTrue(json.contains("execute_jmeter_test"), json);
                }));
    }

    @Test
    void disabledFlag_yieldsNoServers() {
        withProperty(McpConfigProvider.JMETER_DIR_PROP, "/srv/jmeter-mcp", () ->
                withProperty(McpConfigProvider.ENABLED_PROP, "false", () ->
                        assertTrue(McpConfigProvider.configuredServers().isEmpty())));
    }

    static void withProperty(String key, String value, Runnable body) {
        String prev = JMeterUtils.getProperty(key);
        try {
            JMeterUtils.setProperty(key, value);
            body.run();
        } finally {
            if (prev == null) {
                JMeterUtils.getJMeterProperties().remove(key);
            } else {
                JMeterUtils.setProperty(key, prev);
            }
        }
    }
}
