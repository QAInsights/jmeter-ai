package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the JSON config file passed to {@code @playwright/mcp --config}.
 * <p>
 * Three settings here are not cosmetic; each fixes a failure that is otherwise silent:
 * <ul>
 *   <li><strong>{@code proxy.bypass = "<-loopback>"}</strong> - Chromium bypasses the proxy
 *       for localhost and loopback addresses by default. Recording an application running
 *       on the tester's own machine would capture <em>nothing at all</em>, with no error.
 *       The {@code <-loopback>} token removes loopback from that implicit bypass list.</li>
 *   <li><strong>{@code ignoreHTTPSErrors} + {@code --ignore-certificate-errors}</strong> -
 *       the recorder is a MITM proxy presenting its own certificate. Without these the
 *       browser refuses every HTTPS page.</li>
 *   <li><strong>{@code --block-service-workers}</strong> - a service worker serves requests
 *       from its own cache without touching the network, so those requests never reach the
 *       proxy and vanish from the recording.</li>
 * </ul>
 */
public final class PlaywrightMcpConfigWriter {

    /** Forces Chromium to send loopback traffic through the proxy. */
    static final String BYPASS_ALLOW_LOOPBACK = "<-loopback>";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlaywrightMcpConfigWriter() {
    }

    /**
     * Writes the config file, creating parent directories as needed.
     *
     * @param configFile where to write
     * @param options    the session settings
     * @return {@code configFile}, for chaining
     * @throws McpException if the file cannot be written
     */
    public static Path write(Path configFile, PlaywrightMcpOptions options) {
        if (configFile == null) {
            throw new IllegalArgumentException("configFile must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        try {
            Path parent = configFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(buildConfig(options));
            Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
            return configFile;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the Playwright MCP config to "
                    + configFile, e);
        }
    }

    /** Builds the config tree. Exposed for tests so they need not touch the file system. */
    static ObjectNode buildConfig(PlaywrightMcpOptions options) {
        ObjectNode root = MAPPER.createObjectNode();

        ObjectNode browser = root.putObject("browser");
        browser.put("browserName", "chromium");
        // An isolated profile keeps the user's real cookies and history out of the recording.
        browser.put("isolated", true);

        ObjectNode launchOptions = browser.putObject("launchOptions");
        launchOptions.put("headless", options.headless());

        ObjectNode proxy = launchOptions.putObject("proxy");
        proxy.put("server", "http://127.0.0.1:" + options.proxyPort());
        proxy.put("bypass", BYPASS_ALLOW_LOOPBACK);

        ArrayNode args = launchOptions.putArray("args");
        args.add("--ignore-certificate-errors");
        args.add("--block-service-workers");

        ObjectNode contextOptions = browser.putObject("contextOptions");
        contextOptions.put("ignoreHTTPSErrors", true);

        if (!options.allowedOrigins().isEmpty()) {
            ArrayNode allowed = root.putObject("network").putArray("allowedOrigins");
            options.allowedOrigins().forEach(allowed::add);
        }

        if (options.outputDir() != null) {
            root.put("outputDir", options.outputDir().toAbsolutePath().toString());
        }
        return root;
    }
}
