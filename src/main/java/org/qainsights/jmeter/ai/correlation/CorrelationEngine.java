package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.JMeter;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.*;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;
import javax.xml.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CorrelationEngine {

    private static final Logger log = LoggerFactory.getLogger(CorrelationEngine.class);

    private final CorrelationConfig config = new CorrelationConfig();

    private int samplersFound;
    private int resultsCaptured;
    private int candidatesFound;
    private int reusedFound;

    public int getSamplersFound() { return samplersFound; }
    public int getResultsCaptured() { return resultsCaptured; }
    public int getCandidatesFound() { return candidatesFound; }
    public int getReusedFound() { return reusedFound; }

    public List<CorrelationCandidate> runAndCorrelate() throws Exception {
        List<AbstractSampler> samplers = collectSamplers();
        samplersFound = samplers.size();
        if (samplers.isEmpty()) return Collections.emptyList();
        List<SampleResult> results = replayTestPlan();
        resultsCaptured = results.size();
        return correlate(samplers, results);
    }

    public List<CorrelationCandidate> correlateFromJtl(Path jtlPath) throws Exception {
        List<AbstractSampler> samplers = collectSamplers();
        samplersFound = samplers.size();
        List<SampleResult> results = parseJtl(jtlPath);
        resultsCaptured = results.size();
        return correlate(samplers, results);
    }

    private List<CorrelationCandidate> correlate(List<AbstractSampler> samplers, List<SampleResult> results) {
        List<CorrelationCandidate> candidates = detectCandidates(samplers, results);
        candidatesFound = candidates.size();
        crossReference(candidates, samplers);
        List<CorrelationCandidate> reused = new ArrayList<>();
        for (CorrelationCandidate c : candidates) {
            if (c.getUsageCount() > 0) { generatePattern(c); reused.add(c); }
        }
        reusedFound = reused.size();
        return reused;
    }

    private List<AbstractSampler> collectSamplers() {
        List<AbstractSampler> samplers = new ArrayList<>();
        JMeterTreeModel model = GuiPackage.getInstance().getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        Enumeration<TreeNode> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) nodes.nextElement();
            if (node.getTestElement() instanceof AbstractSampler) {
                samplers.add((AbstractSampler) node.getTestElement());
            }
        }
        return samplers;
    }

    private List<SampleResult> replayTestPlan() throws Exception {
        GuiPackage guiPackage = GuiPackage.getInstance();
        HashTree testPlanTree = guiPackage.getTreeModel().getTestPlan();
        ListedHashTree clonedTree = (ListedHashTree) testPlanTree.clone();
        HashTree execTree = JMeter.convertSubTree(clonedTree, true);
        CountDownLatch done = new CountDownLatch(1);
        Collector.prepare();
        Ender.prepare(done);
        for (Object key : execTree.list()) {
            HashTree sub = execTree.getTree(key);
            if (sub == null) continue;
            sub.add(new Collector());
            sub.add(new Ender());
            for (Object subKey : sub.list()) {
                if (subKey instanceof ThreadGroup) {
                    ThreadGroup tg = (ThreadGroup) subKey;
                    tg.setNumThreads(1);
                    if (tg.getSamplerController() instanceof LoopController) {
                        LoopController lc = (LoopController) tg.getSamplerController();
                        lc.setLoops(1);
                        lc.setContinueForever(false);
                    }
                }
            }
            break;
        }
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(execTree);
        engine.run();
        done.await(300, TimeUnit.SECONDS);
        return Collector.getResults();
    }

    private List<SampleResult> parseJtl(Path jtlPath) throws Exception {
        byte[] bytes = Files.readAllBytes(jtlPath);
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) throw new IOException("JTL file is empty");
        if (!content.startsWith("<?xml") && !content.startsWith("<testResults")) {
            throw new IOException("JTL file is not XML. CSV JTL files are not supported.");
        }
        List<SampleResult> results = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        String label = null, responseHeaders = "", requestHeaders = "", responseData = "", requestData = "", url = "";
        long timestamp = 0;
        String currentElement = null;
        StringBuilder textBuf = new StringBuilder();
        int depth = 0;
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT:
                    String name = reader.getLocalName();
                    if ("httpSample".equals(name) || "sample".equals(name)) {
                        if (depth == 0) {
                            label = getAttr(reader, "lb");
                            timestamp = parseLong(getAttr(reader, "ts"));
                            responseHeaders = ""; requestHeaders = ""; responseData = ""; requestData = "";
                            url = getAttr(reader, "url");
                        }
                        depth++;
                    } else if (depth == 1) {
                        currentElement = name; textBuf.setLength(0);
                    }
                    break;
                case XMLStreamConstants.CHARACTERS:
                    if (currentElement != null) textBuf.append(reader.getText());
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    String endName = reader.getLocalName();
                    if ("httpSample".equals(endName) || "sample".equals(endName)) {
                        depth--;
                        if (depth == 0) {
                            SampleResult sr = new SampleResult();
                            sr.setSampleLabel(label != null ? label : "");
                            sr.setStampAndTime(timestamp, 0);
                            sr.setResponseData(responseData, StandardCharsets.UTF_8.name());
                            sr.setResponseHeaders(responseHeaders);
                            sr.setRequestHeaders(requestHeaders);
                            sr.setSamplerData(requestData);
                            if (!url.isEmpty()) sr.setURL(new java.net.URL(url));
                            sr.setSuccessful(true);
                            results.add(sr);
                        }
                    } else if (currentElement != null && depth == 1) {
                        String text = textBuf.toString();
                        switch (currentElement) {
                            case "responseHeader": responseHeaders = text; break;
                            case "requestHeader": requestHeaders = text; break;
                            case "responseData": responseData = text; break;
                            case "samplerData": requestData = text; break;
                            case "queryString": requestData = text; break;
                            case "java.net.URL": case "url": url = text; break;
                        }
                        currentElement = null;
                    }
                    break;
            }
        }
        reader.close();
        results.sort(Comparator.comparingLong(SampleResult::getTimeStamp));
        return results;
    }

    private static String getAttr(XMLStreamReader r, String n) {
        for (int i = 0; i < r.getAttributeCount(); i++)
            if (n.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        return "";
    }
    private static long parseLong(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    private List<CorrelationCandidate> detectCandidates(List<AbstractSampler> samplers, List<SampleResult> results) {
        List<CorrelationCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            SampleResult result = results.get(i);
            String body = result.getResponseDataAsString();
            String respHeaders = result.getResponseHeaders();
            int idx = findSampler(samplers, result.getSampleLabel(), i);
            if (idx < 0) continue;
            AbstractSampler sampler = samplers.get(idx);

            if (respHeaders != null) {
                Matcher cm = Pattern.compile("(?i)^Set-Cookie:\\s*([^=]+)=([^;]+)", Pattern.MULTILINE).matcher(respHeaders);
                while (cm.find()) {
                    String n = cm.group(1).trim(), v = cm.group(2).trim();
                    if (config.getKnownTokens().contains(n.toLowerCase(Locale.ROOT)) && ok(v)) {
                        String key = idx + "|" + n.toLowerCase(Locale.ROOT) + "|Response Header: Set-Cookie";
                        if (seen.add(key)) candidates.add(mk(n, v, sampler, idx, "Response Header: Set-Cookie"));
                    }
                }
            }
            if (body != null) {
                for (String token : config.getKnownTokens()) {
                    String qt = Pattern.quote(token);
                    String[][] patterns = {
                        {"\"" + qt + "\"\\s*:\\s*\"([^\"]+)\"", "Response Body (JSON)"},
                        {";" + qt + "=([^;/?#\"'<>\\s]+)", "Response Body (URL path)"},
                        {"(?<![A-Za-z0-9_.-])" + qt + "=([^&;\\s\"'<>]+)", "Response Body (form)"},
                        {"<input[^>]*name=[\"']" + qt + "[\"'][^>]*value=[\"']([^\"']+)[\"']", "Response Body (hidden input)"}
                    };
                    for (String[] pp : patterns) {
                        Matcher m = Pattern.compile(pp[0], Pattern.CASE_INSENSITIVE).matcher(body);
                        while (m.find()) {
                            String v = m.group(1);
                            String key = idx + "|" + token + "|" + pp[1];
                            if (ok(v) && seen.add(key))
                                candidates.add(mk(token, v, sampler, idx, pp[1]));
                        }
                    }
                }
                for (CorrelationConfig.CustomPattern cp : config.getCustomPatterns()) {
                    Matcher m = cp.getPattern().matcher(body);
                    while (m.find()) {
                        String v = m.group();
                        String key = idx + "|" + cp.getName().toLowerCase(Locale.ROOT) + "|Response Body (custom)";
                        if (ok(v) && seen.add(key))
                            candidates.add(mk(cp.getName(), v, sampler, idx, "Response Body (custom)"));
                    }
                }
            }
        }
        return candidates;
    }

    private CorrelationCandidate mk(String n, String v, AbstractSampler s, int i, String loc) {
        CorrelationCandidate c = new CorrelationCandidate();
        c.setParameterName(n); c.setSampleValue(v); c.setSourceSamplerName(s.getName());
        c.setSourceSamplerIndex(i); c.setSourceLocation(loc);
        return c;
    }
    private int findSampler(List<AbstractSampler> samplers, String label, int fb) {
        if (label == null) return fb < samplers.size() ? fb : -1;
        for (int i = 0; i < samplers.size(); i++) if (label.equals(samplers.get(i).getName())) return i;
        return fb < samplers.size() ? fb : -1;
    }
    private boolean ok(String v) {
        return v != null && v.length() >= config.getMinValueLength()
                && !config.getExcludeValues().contains(v.toLowerCase(Locale.ROOT)) && !v.matches("\\d+");
    }

    private void crossReference(List<CorrelationCandidate> candidates, List<AbstractSampler> samplers) {
        for (CorrelationCandidate c : candidates) {
            for (int i = c.getSourceSamplerIndex() + 1; i < samplers.size(); i++) {
                AbstractSampler s = samplers.get(i);
                boolean found = false;
                if (s instanceof HTTPSamplerBase) {
                    HTTPSamplerBase http = (HTTPSamplerBase) s;
                    Arguments args = http.getArguments();
                    if (args != null) {
                        for (int j = 0; j < args.getArgumentCount(); j++) {
                            org.apache.jmeter.config.Argument a = args.getArgument(j);
                            if (a.getName() != null && a.getName().equalsIgnoreCase(c.getParameterName())) { found = true; break; }
                            if (c.getSampleValue() != null && c.getSampleValue().length() > 5
                                    && a.getValue() != null && a.getValue().contains(c.getSampleValue())) { found = true; break; }
                        }
                    }
                    if (!found && c.getSampleValue() != null && c.getSampleValue().length() > 5) {
                        String path = http.getPath();
                        if (path != null && path.contains(c.getSampleValue())) found = true;
                    }
                }
                if (found) { c.addTargetSamplerName(s.getName()); c.addTargetSamplerIndex(i); }
            }
        }
    }

    private void generatePattern(CorrelationCandidate c) {
        String paramName = c.getParameterName();
        c.setVariableName(toVarName(paramName));
        String loc = c.getSourceLocation();
        c.setExtractorType("regex");
        
        // Make the pattern flexible for parameters with leading underscores
        // Strip leading underscores and make them optional in the regex
        // e.g., _sourcePage or __token in config should match sourcepage, _sourcepage, __sourcepage, etc. in HTML
        String cleanName = paramName.replaceAll("^_+", ""); // Remove all leading underscores
        String esc = escapeRegex(cleanName);
        
        // Add optional underscore prefix pattern: _* matches zero or more underscores
        String flexibleEsc = "_*" + esc;
        
        // Use (?i) for case-insensitive matching and (?s) for DOTALL mode
        if (loc != null && loc.contains("Set-Cookie"))
            c.setExtractionPattern("(?i)Set-Cookie:\\s*" + flexibleEsc + "=([^;]+)");
        else if (loc != null && loc.contains("hidden input"))
            // (?is) = case-insensitive + DOTALL mode to handle newlines and case variations
            c.setExtractionPattern("(?is)<input[^>]*?name=[\"']" + flexibleEsc + "[\"'][^>]*?value=[\"']([^\"']+)[\"']");
        else if (loc != null && loc.contains("JSON"))
            c.setExtractionPattern("(?i)\"" + flexibleEsc + "\"\\s*:\\s*\"([^\"]+)\"");
        else if (loc != null && loc.contains("URL path"))
            c.setExtractionPattern("(?i);" + flexibleEsc + "=([^;/?#\"'<>\\s]+)");
        else
            c.setExtractionPattern("(?i)" + flexibleEsc + "=([^&;\"'<>\\s]+)");
    }

    private static String escapeRegex(String s) { return s.replaceAll("([\\\\\\[\\](){}.*+?^$|])", "\\\\$1"); }
    private static String toVarName(String s) { return s.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toLowerCase(Locale.ROOT); }

    public static class Collector extends AbstractTestElement implements SampleListener {
        private static volatile List<SampleResult> shared;
        public static void prepare() { 
            // Clear any existing results to prevent duplicates across multiple runs
            if (shared != null) {
                shared.clear();
            }
            shared = Collections.synchronizedList(new ArrayList<>()); 
        }
        public static List<SampleResult> getResults() { return shared != null ? new ArrayList<>(shared) : Collections.emptyList(); }
        @Override public void sampleOccurred(SampleEvent e) {
            if (shared != null) {
                SampleResult r = e.getResult();
                if (r != null && r.getParent() == null) shared.add(r);
            }
        }
        @Override public void sampleStarted(SampleEvent e) {}
        @Override public void sampleStopped(SampleEvent e) {}
    }

    public static class Ender extends AbstractTestElement implements TestStateListener {
        private static volatile CountDownLatch latch;
        public static void prepare(CountDownLatch l) { latch = l; }
        @Override public void testEnded() { if (latch != null) latch.countDown(); }
        @Override public void testEnded(String host) { testEnded(); }
        @Override public void testStarted() {}
        @Override public void testStarted(String host) {}
    }
}
