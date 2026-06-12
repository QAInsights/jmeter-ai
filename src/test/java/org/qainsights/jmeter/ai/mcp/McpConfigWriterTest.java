package org.qainsights.jmeter.ai.mcp;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.claudecode.BaseCliAdapter;
import org.qainsights.jmeter.ai.claudecode.KiroCliAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigWriterTest {

    @BeforeAll
    static void initProps() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File tmp = File.createTempFile("jmeter-test", ".properties");
            tmp.deleteOnExit();
            JMeterUtils.loadJMeterProperties(tmp.getAbsolutePath());
        }
    }

    @Test
    void render_producesMcpServersDocument() {
        List<McpServerConfig> servers = Collections.singletonList(
                new McpServerConfig("jmeter", "uv")
                        .args(Arrays.asList("--directory", "/srv", "run", "jmeter_server.py")));
        String doc = McpConfigWriter.render(servers);
        assertTrue(doc.contains("\"mcpServers\""), doc);
        assertTrue(doc.contains("\"jmeter\""), doc);
        assertTrue(doc.trim().startsWith("{") && doc.trim().endsWith("}"), doc);
    }

    @Test
    void writeFor_kiroAdapter_writesWorkspaceMcpJson(@TempDir Path dir) throws IOException {
        McpConfigProviderTest.withProperty(McpConfigProvider.JMETER_DIR_PROP, "/srv/jmeter-mcp", () -> {
            Path written = McpConfigWriter.writeFor(new KiroCliAdapter(), dir.toFile());
            assertNotNull(written);
            assertTrue(written.endsWith(Paths.get(".kiro", "settings", "mcp.json")), written.toString());
        });

        Path expected = dir.resolve(".kiro").resolve("settings").resolve("mcp.json");
        assertTrue(Files.exists(expected));
        String content = new String(Files.readAllBytes(expected), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"jmeter\""), content);
        assertTrue(content.contains("/srv/jmeter-mcp"), content);
    }

    @Test
    void writeFor_returnsNullWhenNoServersConfigured(@TempDir Path dir) {
        // No jmeter.dir set -> nothing configured -> nothing written.
        assertNull(McpConfigWriter.writeFor(new KiroCliAdapter(), dir.toFile()));
    }

    @Test
    void writeFor_returnsNullWhenAdapterDoesNotSupportMcp(@TempDir Path dir) {
        BaseCliAdapter noMcp = new BaseCliAdapter() {
            @Override
            public String getName() {
                return "NoMcpCli";
            }

            @Override
            public boolean detect() {
                return true;
            }
        };
        McpConfigProviderTest.withProperty(McpConfigProvider.JMETER_DIR_PROP, "/srv/jmeter-mcp", () ->
                assertNull(McpConfigWriter.writeFor(noMcp, dir.toFile())));
    }
}
