package org.qainsights.jmeter.ai.record;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives JMeter's native {@link ProxyControl} so recorded traffic becomes real HTTP
 * samplers in the test plan, and re-points the capture target as the agent moves between
 * business steps.
 * <p>
 * Three behaviours here are dictated by how {@code ProxyControl} actually works, each
 * verified against the real class rather than assumed:
 * <ul>
 *   <li>The {@code ProxyControl} element is inserted <em>into the tree</em>. It discovers
 *       its {@code SampleListener}s by looking up its own node, so a detached instance
 *       silently produces no JTL and therefore no correlation input. The node is removed
 *       again in {@link #stop()} so the user's plan is left clean.</li>
 *   <li>Grouping is {@code DONT_GROUP}. Our business steps come from {@link RecordingScaffold},
 *       so ProxyControl's own time-gap grouping would fight them and nest controllers.</li>
 *   <li>{@code startProxy()} always initialises the MITM keystore by shelling out to
 *       {@code keytool} with {@code ${JMETER_HOME}/bin} as its working directory, so that
 *       directory must exist. {@link #checkPrerequisites()} reports this up front instead of
 *       letting it surface as an opaque "CreateProcess error=267".</li>
 * </ul>
 */
public final class JMeterProxyRecorder {

    private static final Logger log = LoggerFactory.getLogger(JMeterProxyRecorder.class);

    /** ProxyControl's grouping constants are private; this is its "Do not group samplers". */
    static final int GROUPING_DONT_GROUP = 0;

    private static final String RECORDER_NAME = "Feather Wand Recorder";

    private final JMeterTreeModel model;
    private final ProxyControl proxy;
    private final RecordingSampleCounter counter = new RecordingSampleCounter();

    private JMeterTreeNode proxyNode;
    private int port;
    private volatile boolean running;

    public JMeterProxyRecorder(JMeterTreeModel model) {
        this(model, new ProxyControl());
    }

    JMeterProxyRecorder(JMeterTreeModel model, ProxyControl proxy) {
        if (model == null) {
            throw new RecordingException("Cannot record without a tree model");
        }
        this.model = model;
        this.proxy = proxy;
    }

    /**
     * Verifies the host can actually start a recorder, so failures are reported during
     * preflight rather than mid-session.
     *
     * @throws RecordingException with an actionable message when a prerequisite is missing
     */
    public static void checkPrerequisites() {
        String jmeterHome = JMeterUtils.getJMeterHome();
        if (jmeterHome == null || jmeterHome.trim().isEmpty()) {
            throw new RecordingException("JMeter home is not set, so the recorder cannot create its "
                    + "HTTPS certificate. Recording is only supported inside a JMeter process.");
        }
        Path binDir = Paths.get(jmeterHome, "bin");
        if (!Files.isDirectory(binDir)) {
            throw new RecordingException("Expected JMeter's bin directory at " + binDir
                    + ", but it does not exist. The recorder generates its HTTPS certificate there.");
        }
        if (!isKeytoolAvailable()) {
            throw new RecordingException("Could not find 'keytool', which JMeter uses to generate the "
                    + "recording certificate. Run JMeter on a JDK, or put keytool on the PATH.");
        }
    }

    /**
     * Binds an ephemeral port and releases it, returning the number for the recorder to
     * claim. There is an inherent race between release and re-bind; the caller should treat
     * a bind failure as retryable rather than fatal.
     *
     * @return a port number that was free a moment ago
     * @throws RecordingException if no port could be obtained
     */
    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RecordingException("Could not find a free port for the recording proxy", e);
        }
    }

    /**
     * Starts the proxy and begins capturing into {@code initialTarget}.
     *
     * @param initialTarget the controller newly recorded samplers are placed under
     * @param jtlPath       where to write the response-data JTL that correlation reads;
     *                      null disables JTL capture
     * @param port          the port to listen on
     * @throws RecordingException if the proxy could not be started
     */
    public void start(JMeterTreeNode initialTarget, Path jtlPath, int port) {
        start(initialTarget, jtlPath, port, java.util.Collections.emptyList());
    }

    /**
     * Starts the proxy, capturing only URLs matching {@code includePatterns}.
     *
     * @param initialTarget  the controller newly recorded samplers are placed under
     * @param jtlPath        where to write the response-data JTL; null disables JTL capture
     * @param port           the port to listen on
     * @param includePatterns regexes that must match the whole URL for it to be recorded; an
     *                        empty list records everything not excluded
     * @throws RecordingException if the proxy could not be started
     */
    public void start(JMeterTreeNode initialTarget, Path jtlPath, int port,
                      java.util.List<String> includePatterns) {
        if (running) {
            throw new RecordingException("Recorder is already running on port " + this.port);
        }
        if (initialTarget == null) {
            throw new RecordingException("Cannot start recording without a target controller");
        }
        this.port = port;

        configure(initialTarget, port);
        applyIncludePatterns(includePatterns);
        attachToTree(jtlPath);

        try {
            proxy.startProxy();
            running = true;
            log.info("Recording proxy listening on port {}", port);
        } catch (IOException | RuntimeException e) {
            detachFromTree();
            throw new RecordingException("Could not start the recording proxy on port " + port
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Re-points capture at a different controller, which is how a business-step boundary is
     * expressed.
     * <p>
     * Callers should {@link #awaitQuiescence} first: samplers are delivered asynchronously,
     * so swapping while requests are still in flight files them under the wrong step.
     *
     * @param target the controller subsequent samplers are placed under
     */
    public void setTarget(JMeterTreeNode target) {
        if (target == null) {
            throw new RecordingException("Cannot re-point the recorder at a null controller");
        }
        proxy.setTarget(target);
    }

    /**
     * Waits for recorded traffic to go quiet, so a step boundary does not split a burst of
     * requests across two controllers.
     *
     * @param quietMillis   how long the stream must stay silent
     * @param timeoutMillis hard upper bound on the wait
     * @return true if quiet was observed, false on timeout
     */
    public boolean awaitQuiescence(long quietMillis, long timeoutMillis) {
        try {
            return counter.awaitQuiescence(quietMillis, timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stops the proxy and removes the recorder's own elements from the plan.
     * <p>
     * Order matters: {@code stopProxy()} is what tells the JTL listener to flush and close,
     * so the node cannot be detached first. Safe to call more than once.
     */
    public void stop() {
        if (running) {
            try {
                proxy.stopProxy();
            } catch (RuntimeException e) {
                log.warn("Recording proxy did not stop cleanly", e);
            } finally {
                running = false;
            }
        }
        detachFromTree();
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * @return how many samplers have been recorded so far
     */
    public int sampleCount() {
        return counter.count();
    }

    private void configure(JMeterTreeNode initialTarget, int port) {
        proxy.setName(RECORDER_NAME);
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(initialTarget);
        proxy.setPort(String.valueOf(port));
        proxy.setCaptureHttpHeaders(true);
        proxy.setGroupingMode(GROUPING_DONT_GROUP);
        proxy.setSamplerFollowRedirects(true);
        proxy.setSamplerRedirectAutomatically(false);
        proxy.setSamplerDownloadImages(false);
        proxy.setUseKeepAlive(true);
        proxy.setDetectGraphQLRequest(true);
        proxy.setAssertions(false);
        proxy.setRegexMatch(true);
        for (String pattern : RecordingFilters.defaultExcludes()) {
            proxy.addExcludedPattern(pattern);
        }
    }

    /**
     * Once any include pattern is present, {@code ProxyControl} records only URLs that match
     * one, so an empty list must leave the list untouched rather than adding a catch-all.
     */
    private void applyIncludePatterns(java.util.List<String> includePatterns) {
        if (includePatterns == null || includePatterns.isEmpty()) {
            return;
        }
        for (String pattern : includePatterns) {
            if (pattern != null && !pattern.trim().isEmpty()) {
                proxy.addIncludedPattern(pattern.trim());
                log.info("Recording restricted to URLs matching {}", pattern.trim());
            }
        }
    }

    /**
     * Inserts the ProxyControl element plus its listeners into the tree. Required for sample
     * notification; see the class comment.
     */
    private void attachToTree(Path jtlPath) {
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        proxyNode = new JMeterTreeNode(proxy, model);
        model.insertNodeInto(proxyNode, root, root.getChildCount());

        counter.setName("Recording Progress");
        model.insertNodeInto(new JMeterTreeNode(counter, model), proxyNode, 0);

        if (jtlPath != null) {
            model.insertNodeInto(new JMeterTreeNode(buildJtlCollector(jtlPath), model), proxyNode, 1);
        }
    }

    private void detachFromTree() {
        if (proxyNode == null) {
            return;
        }
        try {
            model.removeNodeFromParent(proxyNode);
        } catch (RuntimeException e) {
            log.warn("Could not remove the recorder node from the test plan", e);
        } finally {
            proxyNode = null;
        }
    }

    /**
     * @return a listener writing an XML JTL with response bodies and headers, which is the
     *         input the correlation engine scans for dynamic values
     */
    private static ResultCollector buildJtlCollector(Path jtlPath) {
        SampleSaveConfiguration saveConfig = new SampleSaveConfiguration(true);
        saveConfig.setAsXml(true);
        saveConfig.setResponseData(true);
        saveConfig.setRequestHeaders(true);
        saveConfig.setResponseHeaders(true);
        saveConfig.setSamplerData(true);
        saveConfig.setUrl(true);

        ResultCollector collector = new ResultCollector();
        collector.setName("Recording JTL");
        collector.setFilename(jtlPath.toAbsolutePath().toString());
        collector.setSaveConfig(saveConfig);
        collector.setProperty(TestElement.TEST_CLASS, ResultCollector.class.getName());
        collector.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        return collector;
    }

    private static boolean isKeytoolAvailable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path bundled = Paths.get(javaHome, "bin", isWindows() ? "keytool.exe" : "keytool");
            if (Files.isExecutable(bundled)) {
                return true;
            }
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String executable = isWindows() ? "keytool.exe" : "keytool";
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry != null && !entry.trim().isEmpty()
                    && Files.isExecutable(Paths.get(entry.trim(), executable))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
