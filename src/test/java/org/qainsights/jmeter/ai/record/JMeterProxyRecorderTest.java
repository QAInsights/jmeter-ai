package org.qainsights.jmeter.ai.record;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JMeterProxyRecorder}, covering both the guard rails and one real
 * end-to-end capture: a request driven through the proxy must land as a native HTTP
 * sampler under the business step that was current at the time.
 * <p>
 * No browser and no network are involved - a loopback {@link HttpServer} stands in for
 * the site under test.
 */
class JMeterProxyRecorderTest {

    private static Path jmeterHome;

    private JMeterTreeModel model;
    private RecordingScaffold scaffold;
    private JMeterProxyRecorder recorder;
    private HttpServer origin;

    @BeforeAll
    static void initJMeter() {
        // Must be a stable directory, not a @TempDir: ProxyControl freezes its certificate
        // directory in a static final field on first class load. See RecordingTestSupport.
        jmeterHome = RecordingTestSupport.initJMeterHome();
    }

    @BeforeEach
    void setUp() {
        model = new JMeterTreeModel();
        scaffold = RecordingScaffold.createIn(model, "http://127.0.0.1");
        recorder = new JMeterProxyRecorder(model);
    }

    @AfterEach
    void tearDown() {
        if (recorder != null) {
            recorder.stop();
        }
        if (origin != null) {
            origin.stop(0);
            origin = null;
        }
    }

    // Guard rails -------------------------------------------------------------------

    @Test
    void should_rejectNullModel() {
        assertThrows(RecordingException.class, () -> new JMeterProxyRecorder(null));
    }

    @Test
    void should_rejectNullTarget_when_starting() {
        assertThrows(RecordingException.class,
                () -> recorder.start(null, null, JMeterProxyRecorder.findFreePort()));
    }

    @Test
    void should_rejectNullTarget_when_repointing() {
        assertThrows(RecordingException.class, () -> recorder.setTarget(null));
    }

    @Test
    void should_findUsableFreePort() {
        int port = JMeterProxyRecorder.findFreePort();
        assertTrue(port > 0 && port <= 65535, "expected an ephemeral port, got " + port);
    }

    @Test
    void should_reportNotRunning_beforeStart() {
        assertFalse(recorder.isRunning());
        assertEquals(0, recorder.sampleCount());
    }

    @Test
    void should_beIdempotent_when_stoppedWithoutStarting() {
        assertDoesNotThrow(() -> recorder.stop());
        assertDoesNotThrow(() -> recorder.stop());
    }

    @Test
    void should_reportQuiet_when_nothingHasBeenRecorded() {
        assertTrue(recorder.awaitQuiescence(50, 1_000));
    }

    @Test
    void should_failPrerequisites_when_jmeterBinDirectoryIsMissing() {
        String original = JMeterUtils.getJMeterHome();
        try {
            JMeterUtils.setJMeterHome(jmeterHome.resolve("no-such-install").toString());
            RecordingException e = assertThrows(RecordingException.class,
                    JMeterProxyRecorder::checkPrerequisites);
            assertTrue(e.getMessage().contains("bin"), "message should name the missing directory");
        } finally {
            JMeterUtils.setJMeterHome(original);
        }
    }

    @Test
    void should_passPrerequisites_when_jmeterHomeIsValid() {
        assertDoesNotThrow(JMeterProxyRecorder::checkPrerequisites);
    }

    @Test
    void should_registerIncludePatterns_when_scopeIsGiven() {
        ProxyControl proxy = new ProxyControl();
        JMeterProxyRecorder configured = new JMeterProxyRecorder(model, proxy);
        String pattern = RecordingFilters.includeForHost("petstore.octoperf.com");
        try {
            configured.start(scaffold.addBusinessStep("Home"), null,
                    JMeterProxyRecorder.findFreePort(), java.util.List.of(pattern));

            assertEquals(1, proxy.getIncludePatterns().size(),
                    "the host scope should reach ProxyControl's include list");

            java.util.List<String> registered = new java.util.ArrayList<>();
            org.apache.jmeter.testelement.property.PropertyIterator it =
                    proxy.getIncludePatterns().iterator();
            while (it.hasNext()) {
                registered.add(it.next().getStringValue());
            }
            assertEquals(java.util.List.of(pattern), registered);
        } finally {
            configured.stop();
        }
    }

    @Test
    void should_leaveIncludeListEmpty_when_noScopeIsGiven() {
        // Critical: ProxyControl records ONLY matching URLs once any include pattern exists,
        // so adding a catch-all here would silently change filtering semantics.
        ProxyControl proxy = new ProxyControl();
        JMeterProxyRecorder configured = new JMeterProxyRecorder(model, proxy);
        try {
            configured.start(scaffold.addBusinessStep("Home"), null,
                    JMeterProxyRecorder.findFreePort(), java.util.Collections.emptyList());

            assertTrue(proxy.getIncludePatterns().isEmpty());
        } finally {
            configured.stop();
        }
    }

    @Test
    void should_ignoreBlankIncludePatterns() {
        ProxyControl proxy = new ProxyControl();
        JMeterProxyRecorder configured = new JMeterProxyRecorder(model, proxy);
        try {
            configured.start(scaffold.addBusinessStep("Home"), null,
                    JMeterProxyRecorder.findFreePort(), java.util.Arrays.asList("  ", null));

            assertTrue(proxy.getIncludePatterns().isEmpty(),
                    "a blank pattern would match nothing and silently record zero requests");
        } finally {
            configured.stop();
        }
    }

    @Test
    void should_applyRecordingConfiguration_when_started() {
        ProxyControl proxy = new ProxyControl();
        JMeterProxyRecorder configured = new JMeterProxyRecorder(model, proxy);
        int port = JMeterProxyRecorder.findFreePort();
        try {
            configured.start(scaffold.addBusinessStep("Home"), null, port);

            assertEquals(JMeterProxyRecorder.GROUPING_DONT_GROUP, proxy.getGroupingMode(),
                    "our business steps replace ProxyControl's own grouping");
            assertTrue(proxy.getCaptureHttpHeaders());
            assertFalse(proxy.getExcludePatterns().isEmpty(), "default exclusions should be applied");
            assertEquals(port, configured.port());
            assertTrue(configured.isRunning());
        } finally {
            configured.stop();
        }
    }

    @Test
    void should_rejectSecondStart_when_alreadyRunning() {
        int port = JMeterProxyRecorder.findFreePort();
        recorder.start(scaffold.addBusinessStep("Home"), null, port);

        assertThrows(RecordingException.class,
                () -> recorder.start(scaffold.addBusinessStep("Other"), null,
                        JMeterProxyRecorder.findFreePort()));
    }

    // Tree lifecycle ----------------------------------------------------------------

    @Test
    void should_removeItsOwnElementsFromThePlan_when_stopped() {
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        int before = root.getChildCount();

        recorder.start(scaffold.addBusinessStep("Home"), null, JMeterProxyRecorder.findFreePort());
        assertEquals(before + 1, root.getChildCount(),
                "ProxyControl must be in the tree while recording, or listeners are never notified");

        recorder.stop();
        assertEquals(before, root.getChildCount(),
                "the recorder should not leave its own machinery in the user's plan");
        assertFalse(recorder.isRunning());
    }

    // End-to-end --------------------------------------------------------------------

    @Test
    void should_recordSamplerIntoCurrentBusinessStep_when_requestGoesThroughProxy() throws Exception {
        origin = startOriginServer();
        int originPort = origin.getAddress().getPort();
        Path jtl = RecordingTestSupport.artifactDir("business-steps").resolve("recording.jtl");

        JMeterTreeNode browse = scaffold.addBusinessStep("Browse Electronics");
        JMeterTreeNode cart = scaffold.addBusinessStep("Add To Cart");
        recorder.start(browse, jtl, JMeterProxyRecorder.findFreePort());

        HttpClient client = proxiedClient(recorder.port());
        get(client, originPort, "/electronics?q=iphone");
        assertNotNull(awaitFirstChild(browse), "first request should land in the Browse step");

        recorder.awaitQuiescence(100, 5_000);
        recorder.setTarget(cart);

        get(client, originPort, "/cart");
        JMeterTreeNode cartSampler = awaitFirstChild(cart);

        assertNotNull(cartSampler, "traffic after the boundary belongs to the next step");
        assertEquals(1, browse.getChildCount(), "the earlier step must not absorb later traffic");
        assertInstanceOf(HTTPSamplerBase.class, cartSampler.getTestElement());
        assertEquals("/cart", ((HTTPSamplerBase) cartSampler.getTestElement()).getPath());
        assertEquals(2, recorder.sampleCount());
    }

    @Test
    void should_captureRequest_when_hostIncludePatternIsApplied() throws Exception {
        // The gap that shipped a broken recording: the pattern was only ever asserted to
        // reach ProxyControl's include list, never to actually match a real request.
        origin = startOriginServer();
        int originPort = origin.getAddress().getPort();

        JMeterTreeNode step = scaffold.addBusinessStep("Home");
        recorder.start(step, null, JMeterProxyRecorder.findFreePort(),
                java.util.List.of(RecordingFilters.includeForHost("127.0.0.1")));

        HttpClient client = proxiedClient(recorder.port());
        get(client, originPort, "/electronics");

        assertNotNull(awaitFirstChild(step),
                "the include pattern must match ProxyControl's match URL, which has no scheme");
    }

    @Test
    void should_excludeOtherHosts_when_hostIncludePatternIsApplied() throws Exception {
        origin = startOriginServer();
        int originPort = origin.getAddress().getPort();

        JMeterTreeNode step = scaffold.addBusinessStep("Home");
        recorder.start(step, null, JMeterProxyRecorder.findFreePort(),
                java.util.List.of(RecordingFilters.includeForHost("example.invalid")));

        HttpClient client = proxiedClient(recorder.port());
        get(client, originPort, "/electronics");
        recorder.awaitQuiescence(100, 3_000);

        assertEquals(0, step.getChildCount(),
                "traffic to a host outside the scope must not be recorded");
    }

    @Test
    void should_notRecordStaticAssets_when_defaultFiltersApply() throws Exception {
        origin = startOriginServer();
        int originPort = origin.getAddress().getPort();

        JMeterTreeNode step = scaffold.addBusinessStep("Home");
        recorder.start(step, null, JMeterProxyRecorder.findFreePort());

        HttpClient client = proxiedClient(recorder.port());
        get(client, originPort, "/assets/app.js");
        get(client, originPort, "/electronics");

        JMeterTreeNode recorded = awaitFirstChild(step);
        assertNotNull(recorded);
        assertEquals("/electronics", ((HTTPSamplerBase) recorded.getTestElement()).getPath(),
                "the .js request should have been filtered out");
        assertEquals(1, step.getChildCount());
    }

    // Helpers -----------------------------------------------------------------------

    private static HttpClient proxiedClient(int proxyPort) {
        return HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort)))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static void get(HttpClient client, int originPort, String path) throws Exception {
        client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + originPort + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JMeterTreeNode awaitFirstChild(JMeterTreeNode parent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (parent.getChildCount() > 0) {
                return (JMeterTreeNode) parent.getChildAt(0);
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static HttpServer startOriginServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<html><body>iphone</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
