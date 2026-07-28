package org.qainsights.jmeter.ai.record;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.RecordingController;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SPIKE: proves {@link ProxyControl} can be driven programmatically from the plugin,
 * with no {@code GuiPackage} and no browser, and that it delivers real
 * {@link HTTPSamplerBase} nodes into a plain {@link JMeterTreeModel}.
 * <p>
 * This is the load-bearing assumption of the Record Mode redesign. If this passes,
 * {@code JMeterProxyRecorder} is viable and fully unit-testable offline.
 */
class ProxyControlSpikeTest {

    private static Path jmeterHome;

    @BeforeAll
    static void initJMeter() {
        // Must be a stable directory, not a @TempDir: ProxyControl freezes its certificate
        // directory in a static final field on first class load. See RecordingTestSupport.
        jmeterHome = RecordingTestSupport.initJMeterHome();
    }

    @Test
    void should_deliverSamplerIntoTreeModel_when_requestGoesThroughProxy() throws Exception {
        HttpServer origin = startOriginServer();
        int originPort = origin.getAddress().getPort();
        int proxyPort = freePort();

        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        RecordingController controller = new RecordingController();
        controller.setName("Recording Controller");
        JMeterTreeNode target = new JMeterTreeNode(controller, model);
        model.insertNodeInto(target, root, 0);

        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(target);
        proxy.setPort(String.valueOf(proxyPort));
        proxy.setCaptureHttpHeaders(true);
        proxy.setGroupingMode(0);
        proxy.setSamplerDownloadImages(false);
        proxy.setSamplerFollowRedirects(true);

        try {
            proxy.startProxy();

            HttpClient client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort)))
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + originPort + "/electronics?q=iphone"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "origin server should have answered through the proxy");

            JMeterTreeNode samplerNode = awaitFirstChild(target);
            assertNotNull(samplerNode, "ProxyControl delivered no sampler into the tree model");
            assertInstanceOf(HTTPSamplerBase.class, samplerNode.getTestElement(),
                    "delivered node should be a native HTTP sampler");

            HTTPSamplerBase sampler = (HTTPSamplerBase) samplerNode.getTestElement();
            assertEquals("GET", sampler.getMethod());
            assertEquals("/electronics", sampler.getPath());
            assertEquals(originPort, sampler.getPort());
        } finally {
            proxy.stopProxy();
            origin.stop(0);
        }
    }

    /**
     * The {@code begin_business_step} mechanism: re-pointing {@link ProxyControl#setTarget}
     * mid-recording must route subsequent samplers into a different Transaction Controller,
     * giving structural (not time-window) grouping. Also confirms POST bodies are captured.
     */
    @Test
    void should_routeSamplersToNewTarget_when_targetIsRepointedMidRecording() throws Exception {
        HttpServer origin = startOriginServer();
        int originPort = origin.getAddress().getPort();
        int proxyPort = freePort();

        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        RecordingController controller = new RecordingController();
        controller.setName("Recording Controller");
        JMeterTreeNode recordingNode = new JMeterTreeNode(controller, model);
        model.insertNodeInto(recordingNode, root, 0);

        JMeterTreeNode browseStep = transactionNode(model, recordingNode, "Browse Electronics", 0);
        JMeterTreeNode cartStep = transactionNode(model, recordingNode, "Add To Cart", 1);

        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setPort(String.valueOf(proxyPort));
        proxy.setCaptureHttpHeaders(true);
        proxy.setGroupingMode(0);
        proxy.setTarget(browseStep);

        try {
            proxy.startProxy();
            HttpClient client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort)))
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + originPort + "/electronics?q=iphone"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertNotNull(awaitFirstChild(browseStep), "first sampler should land in the Browse step");

            // Business-step boundary.
            proxy.setTarget(cartStep);

            client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + originPort + "/electronics"))
                    .POST(HttpRequest.BodyPublishers.ofString("sku=iphone-15&qty=1"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build(), HttpResponse.BodyHandlers.ofString());
            JMeterTreeNode cartSampler = awaitFirstChild(cartStep);

            assertNotNull(cartSampler, "sampler after re-pointing should land in the Add To Cart step");
            assertEquals(1, browseStep.getChildCount(), "Browse step must not receive the later sampler");

            HTTPSamplerBase sampler = (HTTPSamplerBase) cartSampler.getTestElement();
            assertEquals("POST", sampler.getMethod());
            assertTrue(sampler.getArguments().getArgumentsAsMap().containsKey("sku"),
                    "POST form body should have been captured as sampler arguments");
        } finally {
            proxy.stopProxy();
            origin.stop(0);
        }
    }

    /**
     * Correlation input: ProxyControl notifies child {@code SampleListener}s by looking up
     * <em>its own node</em> in the tree model, so the ProxyControl element must itself be in
     * the tree with a {@link ResultCollector} child, or no JTL is ever written.
     */
    @Test
    void should_writeResponseDataJtl_when_resultCollectorIsChildOfInTreeProxyControl() throws Exception {
        HttpServer origin = startOriginServer();
        int originPort = origin.getAddress().getPort();
        int proxyPort = freePort();
        Path jtl = jmeterHome.resolve("recording-" + proxyPort + ".jtl");

        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        RecordingController controller = new RecordingController();
        controller.setName("Recording Controller");
        JMeterTreeNode target = new JMeterTreeNode(controller, model);
        model.insertNodeInto(target, root, 0);

        ProxyControl proxy = new ProxyControl();
        proxy.setName("Feather Wand Recorder");
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(target);
        proxy.setPort(String.valueOf(proxyPort));
        proxy.setCaptureHttpHeaders(true);
        proxy.setGroupingMode(0);

        // ProxyControl must be part of the tree for notifySampleListeners() to find its children.
        JMeterTreeNode proxyNode = new JMeterTreeNode(proxy, model);
        model.insertNodeInto(proxyNode, root, 1);

        CapturingListener listener = new CapturingListener();
        listener.setName("Capturing Listener");
        model.insertNodeInto(new JMeterTreeNode(listener, model), proxyNode, 0);

        // A real ResultCollector alongside it, to prove the JTL file itself gets opened.
        ResultCollector collector = new ResultCollector();
        collector.setName("Recording JTL");
        collector.setFilename(jtl.toAbsolutePath().toString());
        SampleSaveConfiguration saveConfig = new SampleSaveConfiguration(true);
        saveConfig.setAsXml(true);
        saveConfig.setResponseData(true);
        collector.setSaveConfig(saveConfig);
        model.insertNodeInto(new JMeterTreeNode(collector, model), proxyNode, 1);

        try {
            proxy.startProxy();
            HttpClient client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort)))
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + originPort + "/electronics?q=iphone"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertNotNull(awaitFirstChild(target), "sampler should have been delivered");
        } finally {
            proxy.stopProxy();
            origin.stop(0);
        }

        assertFalse(listener.results.isEmpty(),
                "a SampleListener child of the in-tree ProxyControl must be notified");
        SampleResult result = listener.results.get(0);
        assertTrue(result.getUrlAsString().contains("/electronics"),
                "listener should see the recorded URL, got: " + result.getUrlAsString());
        assertTrue(result.getResponseDataAsString().contains("iphone"),
                "listener should see response data - this is what correlation detection reads");
        assertTrue(java.nio.file.Files.exists(jtl), "ResultCollector should have opened " + jtl);
    }

    /**
     * Stand-in for the recording {@code ResultCollector}. Asserting against this rather than a
     * real JTL file keeps the spike independent of {@code SaveService}, whose XStream aliases
     * live in {@code ${JMETER_HOME}/bin/saveservice.properties} and are only registered inside
     * a real JMeter process - outside one, XML JTL serialization fails part-way through.
     */
    private static final class CapturingListener extends AbstractTestElement implements SampleListener {
        private final List<SampleResult> results = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void sampleOccurred(SampleEvent e) {
            results.add(e.getResult());
        }

        @Override
        public void sampleStarted(SampleEvent e) {
            // not used by the recorder
        }

        @Override
        public void sampleStopped(SampleEvent e) {
            // not used by the recorder
        }
    }

    private static JMeterTreeNode transactionNode(JMeterTreeModel model, JMeterTreeNode parent,
                                                  String name, int index) {
        org.apache.jmeter.control.TransactionController tc =
                new org.apache.jmeter.control.TransactionController();
        tc.setName(name);
        JMeterTreeNode node = new JMeterTreeNode(tc, model);
        model.insertNodeInto(node, parent, index);
        return node;
    }

    /** Samplers arrive asynchronously on proxy threads; poll rather than sleep-and-hope. */
    private static JMeterTreeNode awaitFirstChild(JMeterTreeNode parent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (parent.getChildCount() > 0) {
                return (JMeterTreeNode) parent.getChildAt(0);
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static HttpServer startOriginServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/electronics", exchange -> {
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
