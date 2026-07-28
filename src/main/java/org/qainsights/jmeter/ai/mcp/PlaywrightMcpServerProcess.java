package org.qainsights.jmeter.ai.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches {@code @playwright/mcp} as a child process and exposes its stdio pipes for
 * {@link StdioMcpClient}.
 * <p>
 * Two details are easy to get wrong and expensive to debug:
 * <ul>
 *   <li><strong>stderr must be drained continuously.</strong> A child process whose stderr
 *       pipe buffer fills up blocks on write, and since the server is then no longer
 *       reading stdin, the whole session deadlocks with no error. A daemon thread copies
 *       it to the log instead - which doubles as the only visibility into server-side
 *       failures.</li>
 *   <li><strong>stderr must not be merged into stdout.</strong> {@code redirectErrorStream}
 *       would interleave log text with the JSON-RPC stream and corrupt it.</li>
 * </ul>
 */
public final class PlaywrightMcpServerProcess implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightMcpServerProcess.class);

    /** Overrides the launcher, for users with a global or pinned install. */
    public static final String COMMAND_PROPERTY = "jmeter.ai.record.mcp.command";

    private static final String DEFAULT_PACKAGE = "@playwright/mcp@latest";
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final Process process;
    private final Thread stderrPump;

    private PlaywrightMcpServerProcess(Process process) {
        this.process = process;
        this.stderrPump = new Thread(this::pumpStderr, "playwright-mcp-stderr");
        this.stderrPump.setDaemon(true);
        this.stderrPump.start();
    }

    /**
     * Starts the server with the given config file.
     *
     * @param configFile a file previously written by {@link PlaywrightMcpConfigWriter}
     * @return the running process wrapper
     * @throws McpException if the process cannot be started
     */
    public static PlaywrightMcpServerProcess start(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            throw new McpException("Playwright MCP config file not found: " + configFile);
        }
        List<String> command = buildCommand(configFile);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            // Deliberately NOT redirectErrorStream(true): that would corrupt the JSON-RPC stream.
            builder.redirectErrorStream(false);
            Process process = builder.start();
            log.info("Started Playwright MCP: {}", String.join(" ", command));
            return new PlaywrightMcpServerProcess(process);
        } catch (IOException e) {
            throw new McpException("Could not start Playwright MCP using '"
                    + String.join(" ", command) + "'. Node.js and npx must be installed and on "
                    + "the PATH. Cause: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the launch command.
     * <p>
     * On Windows {@code npx} is a {@code .cmd} shim, which {@link ProcessBuilder} cannot
     * execute directly, so it is invoked through {@code cmd /c}.
     *
     * @param configFile the config file to pass through
     * @return the command and its arguments
     */
    static List<String> buildCommand(Path configFile) {
        String override = System.getProperty(COMMAND_PROPERTY, "").trim();
        List<String> command = new ArrayList<>();
        if (!override.isEmpty()) {
            for (String token : override.split("\\s+")) {
                command.add(token);
            }
        } else if (isWindows()) {
            command.add("cmd");
            command.add("/c");
            command.add("npx");
            command.add("-y");
            command.add(DEFAULT_PACKAGE);
        } else {
            command.add("npx");
            command.add("-y");
            command.add(DEFAULT_PACKAGE);
        }
        command.add("--config");
        command.add(configFile.toAbsolutePath().toString());
        return command;
    }

    /**
     * Verifies Node's launcher is present, so a missing prerequisite is reported before a
     * recording session starts rather than as an opaque process failure.
     *
     * @return true if {@code npx} appears to be available
     */
    public static boolean isNpxAvailable() {
        if (!System.getProperty(COMMAND_PROPERTY, "").trim().isEmpty()) {
            return true;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String[] candidates = isWindows()
                ? new String[] {"npx.cmd", "npx.exe", "npx"}
                : new String[] {"npx"};
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            for (String candidate : candidates) {
                if (Files.isRegularFile(Paths.get(entry.trim(), candidate))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Creates a client speaking to this process, using the default request timeout. */
    public StdioMcpClient newClient() {
        return new StdioMcpClient(process.getInputStream(), process.getOutputStream());
    }

    /**
     * Creates a client with an explicit request timeout. Worth raising for the first run of
     * a session, when npx may download the package before the handshake can complete.
     */
    public StdioMcpClient newClient(long timeoutMillis) {
        return new StdioMcpClient(process.getInputStream(), process.getOutputStream(), timeoutMillis);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    private void pumpStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[playwright-mcp] {}", line);
            }
        } catch (IOException e) {
            log.debug("Playwright MCP stderr closed", e);
        }
    }

    /**
     * Stops the server and its entire process tree, escalating to a forced kill if it does
     * not exit promptly.
     * <p>
     * Descendants must be handled explicitly. On Windows the launcher is
     * {@code cmd /c npx ...}, so the direct child is {@code cmd.exe} and destroying it
     * leaves the actual Node server - and the browser it drives - running. The orphan then
     * holds the recording proxy port and the user sees a stale browser window. The same
     * applies to the {@code npx} shim on Unix. Descendants are captured <em>before</em> the
     * parent dies, because afterwards the tree is no longer reachable from it.
     */
    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().collect(Collectors.toList());

        process.destroy();
        try {
            if (!process.waitFor(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Playwright MCP did not exit gracefully; killing it");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }

        for (ProcessHandle child : descendants) {
            if (child.isAlive()) {
                log.debug("Terminating orphaned Playwright MCP child process {}", child.pid());
                child.destroy();
            }
        }
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    /** Exposed so tests can inspect the real process tree. */
    Process rawProcess() {
        return process;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
