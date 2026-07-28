package org.qainsights.jmeter.ai.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link PlaywrightMcpServerProcess} command construction and preflight.
 * <p>
 * Nothing here spawns Node: launching the real server would make the suite depend on a
 * network install of {@code @playwright/mcp}. The process plumbing itself is exercised
 * against a plain Java child process instead.
 */
class PlaywrightMcpServerProcessTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(PlaywrightMcpServerProcess.COMMAND_PROPERTY);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void should_passConfigFileToTheServer(@TempDir Path tempDir) {
        Path config = tempDir.resolve("playwright-mcp.json");

        List<String> command = PlaywrightMcpServerProcess.buildCommand(config);

        int flag = command.indexOf("--config");
        assertTrue(flag >= 0, "the server must be told which config to use");
        assertEquals(config.toAbsolutePath().toString(), command.get(flag + 1));
    }

    @Test
    void should_invokeNpxThroughCmd_onWindows(@TempDir Path tempDir) {
        List<String> command = PlaywrightMcpServerProcess.buildCommand(tempDir.resolve("c.json"));

        if (isWindows()) {
            assertEquals(List.of("cmd", "/c", "npx"), command.subList(0, 3),
                    "npx is a .cmd shim that ProcessBuilder cannot execute directly");
        } else {
            assertEquals("npx", command.get(0));
        }
    }

    @Test
    void should_requestThePlaywrightMcpPackage(@TempDir Path tempDir) {
        List<String> command = PlaywrightMcpServerProcess.buildCommand(tempDir.resolve("c.json"));

        assertTrue(command.stream().anyMatch(t -> t.startsWith("@playwright/mcp")));
        assertTrue(command.contains("-y"), "npx must not block on an install prompt");
    }

    @Test
    void should_honourCommandOverride(@TempDir Path tempDir) {
        System.setProperty(PlaywrightMcpServerProcess.COMMAND_PROPERTY,
                "/usr/local/bin/playwright-mcp --port 0");

        List<String> command = PlaywrightMcpServerProcess.buildCommand(tempDir.resolve("c.json"));

        assertEquals("/usr/local/bin/playwright-mcp", command.get(0));
        assertEquals("--port", command.get(1));
        assertEquals("0", command.get(2));
        assertEquals("--config", command.get(3));
    }

    @Test
    void should_reportAvailable_when_overrideConfigured() {
        System.setProperty(PlaywrightMcpServerProcess.COMMAND_PROPERTY, "my-mcp");

        assertTrue(PlaywrightMcpServerProcess.isNpxAvailable(),
                "an explicit override means the user has told us what to run");
    }

    @Test
    void should_failWithActionableMessage_when_configMissing(@TempDir Path tempDir) {
        McpException e = assertThrows(McpException.class,
                () -> PlaywrightMcpServerProcess.start(tempDir.resolve("absent.json")));

        assertTrue(e.getMessage().contains("config file not found"));
    }

    @Test
    void should_explainNodeRequirement_when_commandCannotBeLaunched(@TempDir Path tempDir)
            throws Exception {
        Path config = Files.writeString(tempDir.resolve("c.json"), "{}");
        System.setProperty(PlaywrightMcpServerProcess.COMMAND_PROPERTY,
                "definitely-not-a-real-executable-xyz");

        McpException e = assertThrows(McpException.class,
                () -> PlaywrightMcpServerProcess.start(config));

        assertTrue(e.getMessage().contains("Node.js"),
                "the user needs to know what to install");
    }

    @Test
    void should_terminateLongRunningChild(@TempDir Path tempDir) throws Exception {
        Path config = Files.writeString(tempDir.resolve("c.json"), "{}");
        Path blocker = writeBlockingScript(tempDir);
        // The command override is split on whitespace, so a path containing spaces cannot
        // be expressed. Skip rather than assert something untrue on such a machine.
        assumeTrue(!blocker.toString().contains(" "), "temp path contains spaces");

        System.setProperty(PlaywrightMcpServerProcess.COMMAND_PROPERTY,
                isWindows() ? "cmd /c " + blocker : "sh " + blocker);

        PlaywrightMcpServerProcess server = PlaywrightMcpServerProcess.start(config);
        assertTrue(server.isAlive(), "the stand-in server should block, not exit on its own");
        assertNotNull(server.newClient());

        server.close();

        assertFalse(server.isAlive(),
                "a lingering server would hold the browser open and the proxy port busy");
        assertDoesNotThrow(server::close, "close must be idempotent");
    }

    @Test
    void should_killTheWholeProcessTree_notJustTheLauncherShim(@TempDir Path tempDir)
            throws Exception {
        // The real launcher is "cmd /c npx ..." on Windows, so the direct child is a shim
        // and the Node server is a grandchild. Destroying only the shim orphans the server,
        // which keeps the browser open and the proxy port bound.
        Path config = Files.writeString(tempDir.resolve("c.json"), "{}");
        Path blocker = writeBlockingScript(tempDir);
        assumeTrue(!blocker.toString().contains(" "), "temp path contains spaces");

        System.setProperty(PlaywrightMcpServerProcess.COMMAND_PROPERTY,
                isWindows() ? "cmd /c " + blocker : "sh " + blocker);

        PlaywrightMcpServerProcess server = PlaywrightMcpServerProcess.start(config);
        List<ProcessHandle> descendants = waitForDescendants(server);
        assumeTrue(!descendants.isEmpty(), "no grandchild was spawned on this platform");

        server.close();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            Thread.sleep(50);
        }
        assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive),
                "the grandchild process survived close(), so a real server would be orphaned");
    }

    private static List<ProcessHandle> waitForDescendants(PlaywrightMcpServerProcess server)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<ProcessHandle> found = server.rawProcess().descendants()
                    .collect(java.util.stream.Collectors.toList());
            if (!found.isEmpty()) {
                return found;
            }
            Thread.sleep(50);
        }
        return List.of();
    }

    /**
     * Writes a script that blocks for a long time via a child process, standing in for the
     * shim-plus-server tree of the real launcher.
     * <p>
     * It must not self-exit: a command such as {@code java -version} would make the
     * termination assertions pass whether or not {@code close()} works.
     */
    private static Path writeBlockingScript(Path dir) throws Exception {
        if (isWindows()) {
            Path script = dir.resolve("blocker.cmd");
            Files.writeString(script, "@echo off\r\nping -n 600 127.0.0.1 >nul\r\n");
            return script;
        }
        Path script = dir.resolve("blocker.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 600\nexit 0\n");
        script.toFile().setExecutable(true);
        return script;
    }
}
