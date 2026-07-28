package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PlaywrightMcpConfigWriter}.
 * <p>
 * Several assertions here guard settings whose absence causes a <em>silent</em> failure -
 * a recording that completes successfully and captures nothing. Those are the ones worth
 * pinning hardest.
 */
class PlaywrightMcpConfigWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void should_routeBrowserThroughRecordingProxy() {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(
                PlaywrightMcpOptions.forProxy(8888));

        assertEquals("http://127.0.0.1:8888",
                config.path("browser").path("launchOptions").path("proxy").path("server").asText());
    }

    @Test
    void should_disableLoopbackBypass_so_localApplicationsAreRecorded() {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(
                PlaywrightMcpOptions.forProxy(8888));

        assertEquals(PlaywrightMcpConfigWriter.BYPASS_ALLOW_LOOPBACK,
                config.path("browser").path("launchOptions").path("proxy").path("bypass").asText(),
                "without <-loopback> Chromium skips the proxy for localhost and records nothing");
    }

    @Test
    void should_tolerateRecorderCertificate() {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(
                PlaywrightMcpOptions.forProxy(8888));
        JsonNode browser = config.path("browser");

        assertTrue(browser.path("contextOptions").path("ignoreHTTPSErrors").asBoolean(),
                "the recorder is a MITM proxy; HTTPS pages fail without this");
        assertTrue(argsOf(config).contains("--ignore-certificate-errors"));
    }

    @Test
    void should_blockServiceWorkers_so_cachedRequestsStillHitTheProxy() {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(
                PlaywrightMcpOptions.forProxy(8888));

        assertTrue(argsOf(config).contains("--block-service-workers"),
                "a service worker answers from its own cache and bypasses the proxy entirely");
    }

    @Test
    void should_useIsolatedProfile() {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(
                PlaywrightMcpOptions.forProxy(8888));

        assertTrue(config.path("browser").path("isolated").asBoolean(),
                "the user's real cookies must not leak into the recording");
    }

    @Test
    void should_respectHeadlessFlag() {
        assertFalse(PlaywrightMcpConfigWriter.buildConfig(PlaywrightMcpOptions.forProxy(8888))
                .path("browser").path("launchOptions").path("headless").asBoolean());

        assertTrue(PlaywrightMcpConfigWriter.buildConfig(
                        new PlaywrightMcpOptions(8888, true, List.of(), null))
                .path("browser").path("launchOptions").path("headless").asBoolean());
    }

    @Test
    void should_includeAllowedOrigins_when_provided() {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(new PlaywrightMcpOptions(
                8888, false, List.of("https://shop.test"), null));

        JsonNode allowed = config.path("network").path("allowedOrigins");
        assertTrue(allowed.isArray());
        assertEquals("https://shop.test", allowed.get(0).asText());
    }

    @Test
    void should_omitNetworkSection_when_noOriginsRestricted() {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(
                PlaywrightMcpOptions.forProxy(8888));

        assertFalse(config.has("network"),
                "an empty allow-list must mean unrestricted, not blocked");
    }

    @Test
    void should_includeOutputDir_when_provided(@TempDir Path tempDir) {
        ObjectNode config = PlaywrightMcpConfigWriter.buildConfig(
                new PlaywrightMcpOptions(8888, false, List.of(), tempDir));

        assertEquals(tempDir.toAbsolutePath().toString(), config.path("outputDir").asText());
    }

    @Test
    void should_writeParseableFileAndCreateParentDirectories(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("nested").resolve("playwright-mcp.json");

        Path written = PlaywrightMcpConfigWriter.write(configFile, PlaywrightMcpOptions.forProxy(9999));

        assertEquals(configFile, written);
        assertTrue(Files.isRegularFile(configFile));
        JsonNode parsed = MAPPER.readTree(Files.readString(configFile));
        assertEquals("http://127.0.0.1:9999",
                parsed.path("browser").path("launchOptions").path("proxy").path("server").asText());
    }

    @Test
    void should_rejectInvalidInput(@TempDir Path tempDir) {
        assertThrows(IllegalArgumentException.class,
                () -> PlaywrightMcpConfigWriter.write(null, PlaywrightMcpOptions.forProxy(8888)));
        assertThrows(IllegalArgumentException.class,
                () -> PlaywrightMcpConfigWriter.write(tempDir.resolve("x.json"), null));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightMcpOptions.forProxy(0));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightMcpOptions.forProxy(70_000));
    }

    @Test
    void should_defensivelyCopyAllowedOrigins() {
        List<String> mutable = new java.util.ArrayList<>(List.of("https://shop.test"));
        PlaywrightMcpOptions options = new PlaywrightMcpOptions(8888, false, mutable, null);
        mutable.clear();

        assertEquals(1, options.allowedOrigins().size());
    }

    private static List<String> argsOf(ObjectNode config) {
        List<String> args = new java.util.ArrayList<>();
        config.path("browser").path("launchOptions").path("args").forEach(a -> args.add(a.asText()));
        return args;
    }
}
