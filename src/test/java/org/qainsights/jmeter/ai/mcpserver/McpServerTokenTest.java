package org.qainsights.jmeter.ai.mcpserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTokenTest {

    @TempDir
    Path dir;

    @Test
    void createsOnceThenReusesTheToken() throws Exception {
        Path file = dir.resolve("nested").resolve("mcp-token");
        String token = McpServerToken.loadOrCreate(file);
        assertTrue(token.matches("[0-9a-f]{64}"));
        assertEquals(token, McpServerToken.loadOrCreate(file));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void replacesAMalformedToken() throws Exception {
        Path file = dir.resolve("mcp-token");
        Files.writeString(file, "short");
        String token = McpServerToken.loadOrCreate(file);
        assertTrue(token.matches("[0-9a-f]{64}"));
        assertEquals(token, Files.readString(file));
    }

    @Test
    void regenerateChangesTheToken() throws Exception {
        Path file = dir.resolve("mcp-token");
        String first = McpServerToken.loadOrCreate(file);
        String second = McpServerToken.regenerate(file);
        assertNotEquals(first, second);
        assertEquals(second, McpServerToken.loadOrCreate(file));
    }
}
