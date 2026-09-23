package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CorrelationInjector} against a real in-memory {@link JMeterTreeModel}
 * (no GUI), verifying extractor insertion and downstream value rewriting.
 */
class CorrelationInjectorTest {

    private final CorrelationInjector injector = new CorrelationInjector();
    private JMeterTreeModel model;
    private JMeterTreeNode threadGroupNode;

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @BeforeEach
    void setUp() {
        model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode testPlanNode = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (child.getTestElement() instanceof TestPlan) testPlanNode = child;
        }
        assertNotNull(testPlanNode);
        ThreadGroup tg = new ThreadGroup();
        tg.setName("Thread Group");
        threadGroupNode = addNode(tg, testPlanNode);
    }

    private JMeterTreeNode addNode(TestElement element, JMeterTreeNode parent) {
        JMeterTreeNode node = new JMeterTreeNode(element, model);
        model.insertNodeInto(node, parent, parent.getChildCount());
        return node;
    }

    private HTTPSamplerProxy addHttp(String name, String path, String... nameValuePairs) {
        HTTPSamplerProxy s = new HTTPSamplerProxy();
        s.setName(name);
        s.setPath(path);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            s.getArguments().addArgument(new HTTPArgument(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        addNode(s, threadGroupNode);
        return s;
    }

    private JMeterTreeNode nodeOf(TestElement element) {
        for (int i = 0; i < threadGroupNode.getChildCount(); i++) {
            JMeterTreeNode n = (JMeterTreeNode) threadGroupNode.getChildAt(i);
            if (n.getTestElement() == element) return n;
        }
        return null;
    }

    private static CorrelationCandidate approved(String param, String value, String source, String var,
                                                 String pattern, String type, String... targets) {
        CorrelationCandidate c = new CorrelationCandidate();
        c.setParameterName(param);
        c.setSampleValue(value);
        c.setSourceSamplerName(source);
        c.setVariableName(var);
        c.setExtractionPattern(pattern);
        c.setExtractorType(type);
        for (String t : targets) c.addTargetSamplerName(t);
        c.setStatus(CorrelationCandidate.Status.APPROVED);
        return c;
    }

    private static String argValue(HTTPSamplerProxy s, String name) {
        for (int i = 0; i < s.getArguments().getArgumentCount(); i++) {
            if (name.equals(s.getArguments().getArgument(i).getName())) {
                return s.getArguments().getArgument(i).getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ apply

    @Test
    void approvedRegexCandidateAddsRegexExtractorUnderSourceAndRewritesTargets() {
        HTTPSamplerProxy login = addHttp("Login", "/login");
        HTTPSamplerProxy dash = addHttp("Dashboard", "/dashboard", "csrf", "stale-token", "other", "keep-me");
        CorrelationCandidate c = approved("csrf", "fresh-token-1", "Login", "csrf",
                "(?i)\"_*csrf\"\\s*:\\s*\"([^\"]+)\"", "regex", "Dashboard");

        assertEquals(1, injector.apply(Collections.singletonList(c), model));

        JMeterTreeNode loginNode = nodeOf(login);
        assertEquals(1, loginNode.getChildCount());
        TestElement extractor = ((JMeterTreeNode) loginNode.getChildAt(0)).getTestElement();
        assertInstanceOf(RegexExtractor.class, extractor);
        assertEquals("Correlation - csrf", extractor.getName());
        assertEquals("csrf", extractor.getPropertyAsString("RegexExtractor.refname"));
        assertEquals("(?i)\"_*csrf\"\\s*:\\s*\"([^\"]+)\"", extractor.getPropertyAsString("RegexExtractor.regex"));
        assertEquals("$1$", extractor.getPropertyAsString("RegexExtractor.template"));
        assertEquals("NOT_FOUND", extractor.getPropertyAsString("RegexExtractor.default"));
        assertEquals("1", extractor.getPropertyAsString("RegexExtractor.match_number"));
        assertEquals("org.apache.jmeter.extractor.gui.RegexExtractorGui", extractor.getPropertyAsString(TestElement.GUI_CLASS));

        assertEquals("${csrf}", argValue(dash, "csrf"));
        assertEquals("keep-me", argValue(dash, "other"));
        assertEquals(0, nodeOf(dash).getChildCount(), "no extractor on target");
    }

    @Test
    void jsonCandidateAddsJsonPostProcessor() {
        HTTPSamplerProxy auth = addHttp("Auth", "/auth");
        addHttp("Api", "/api", "access_token", "old");
        CorrelationCandidate c = approved("access_token", "tok-9f8e7d", "Auth", "access_token",
                "$.access_token", "json", "Api");

        assertEquals(1, injector.apply(Collections.singletonList(c), model));

        TestElement extractor = ((JMeterTreeNode) nodeOf(auth).getChildAt(0)).getTestElement();
        assertInstanceOf(JSONPostProcessor.class, extractor);
        assertEquals("Correlation - access_token", extractor.getName());
        assertEquals("access_token", extractor.getPropertyAsString("JSONPostProcessor.refnames"));
        assertEquals("$.access_token", extractor.getPropertyAsString("JSONPostProcessor.jsonPathExprs"));
        assertEquals("NOT_FOUND", extractor.getPropertyAsString("JSONPostProcessor.defaultValues"));
    }

    @Test
    void nonApprovedCandidatesAreSkipped() {
        HTTPSamplerProxy login = addHttp("Login", "/login");
        HTTPSamplerProxy dash = addHttp("Dashboard", "/dashboard", "csrf", "stale-token");
        CorrelationCandidate pending = approved("csrf", "stale-token", "Login", "csrf", "x=(.*)", "regex", "Dashboard");
        pending.setStatus(CorrelationCandidate.Status.PENDING);
        CorrelationCandidate rejected = approved("csrf", "stale-token", "Login", "csrf", "x=(.*)", "regex", "Dashboard");
        rejected.setStatus(CorrelationCandidate.Status.REJECTED);
        CorrelationCandidate ok = approved("csrf", "stale-token", "Login", "csrf", "x=(.*)", "regex", "Dashboard");

        assertEquals(1, injector.apply(Arrays.asList(pending, rejected, ok), model));

        assertEquals(1, nodeOf(login).getChildCount());
        assertEquals("${csrf}", argValue(dash, "csrf"));
    }

    @Test
    void sourceNotFoundLeavesPlanUntouched() {
        HTTPSamplerProxy login = addHttp("Login", "/login");
        HTTPSamplerProxy dash = addHttp("Dashboard", "/dashboard", "csrf", "stale-token");
        CorrelationCandidate c = approved("csrf", "stale-token", "Missing", "csrf", "x=(.*)", "regex", "Dashboard");

        assertEquals(0, injector.apply(Collections.singletonList(c), model));

        assertEquals(0, nodeOf(login).getChildCount());
        assertEquals("stale-token", argValue(dash, "csrf"));
    }

    @Test
    void targetNotFoundStillCountsExtractorAndTouchesNothingElse() {
        HTTPSamplerProxy login = addHttp("Login", "/login");
        HTTPSamplerProxy dash = addHttp("Dashboard", "/dashboard", "csrf", "stale-token");
        CorrelationCandidate c = approved("csrf", "stale-token", "Login", "csrf", "x=(.*)", "regex", "NoSuchTarget");

        assertEquals(1, injector.apply(Collections.singletonList(c), model));

        assertEquals(1, nodeOf(login).getChildCount());
        assertEquals("stale-token", argValue(dash, "csrf"));
    }

    @Test
    void sourceNameMatchesByLastPathSegment() {
        HTTPSamplerProxy catalog = addHttp("Catalog.action-4", "/actions/Catalog.action");
        CorrelationCandidate c = approved("csrf", "abcdef", "test/actions/Catalog.action-4", "csrf", "x=(.*)", "regex");

        assertEquals(1, injector.apply(Collections.singletonList(c), model));
        assertEquals(1, nodeOf(catalog).getChildCount());
    }

    // --------------------------------------------------------------- findNode

    @Test
    void findNodeExactAndLastSegmentAndMiss() {
        HTTPSamplerProxy a = addHttp("A", "/a");
        HTTPSamplerProxy b = addHttp("B", "/b");
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();

        assertSame(nodeOf(a), injector.findNode(root, "A"));
        assertSame(nodeOf(b), injector.findNode(root, "x/y/B"));
        assertNull(injector.findNode(root, "C"));
        assertNull(injector.findNode(root, "A/B/C"));
    }

    // -------------------------------------------------------- replaceInTarget

    @Test
    void replaceInTargetRewritesPath() {
        HTTPSamplerProxy s = addHttp("Detail", "/items;jsessionid=SESS-42-XYZ/detail");
        injector.replaceInTarget((JMeterTreeNode) model.getRoot(), "Detail", "jsessionid", "SESS-42-XYZ", "jsessionid");
        assertEquals("/items;jsessionid=${jsessionid}/detail", s.getPath());
    }

    @Test
    void replaceInTargetRewritesArgumentMatchedByNameCaseInsensitively() {
        HTTPSamplerProxy s = addHttp("Submit", "/submit", "CSRF", "completely-different-value");
        injector.replaceInTarget((JMeterTreeNode) model.getRoot(), "Submit", "csrf", "abcdef", "csrf");
        assertEquals("${csrf}", argValue(s, "CSRF"));
    }

    @Test
    void replaceInTargetRewritesArgumentValueContainingSampleValue() {
        HTTPSamplerProxy s = addHttp("Submit", "/submit", "redirect", "/home?sid=SESS-42-XYZ&x=1");
        injector.replaceInTarget((JMeterTreeNode) model.getRoot(), "Submit", "sid", "SESS-42-XYZ", "sid");
        assertEquals("/home?sid=${sid}&x=1", argValue(s, "redirect"));
    }

    @Test
    void replaceInTargetLeavesUnrelatedArgumentsAlone() {
        HTTPSamplerProxy s = addHttp("Submit", "/submit", "a", "unrelated", "b", "SESS-42-XY");
        injector.replaceInTarget((JMeterTreeNode) model.getRoot(), "Submit", "sid", "SESS-42-XYZ", "sid");
        assertEquals("unrelated", argValue(s, "a"));
        assertEquals("SESS-42-XY", argValue(s, "b"));
        assertEquals("/submit", s.getPath());
    }

    @Test
    void replaceInTargetRewritesGenericPropertyOnNonHttpSampler() {
        DebugSampler debug = new DebugSampler();
        debug.setName("Debug");
        debug.setProperty("custom.body", "token=SESS-42-XYZ;end");
        addNode(debug, threadGroupNode);

        injector.replaceInTarget((JMeterTreeNode) model.getRoot(), "Debug", "token", "SESS-42-XYZ", "token");

        assertEquals("token=${token};end", debug.getPropertyAsString("custom.body"));
        assertEquals("Debug", debug.getName());
    }

    @Test
    void replaceInTargetSubstringCollisionRewritesEveryOccurrence() {
        HTTPSamplerProxy s = addHttp("Submit", "/submit", "note", "id=abcdef and again abcdef");
        injector.replaceInTarget((JMeterTreeNode) model.getRoot(), "Submit", "sid", "abcdef", "sid");
        assertEquals("id=${sid} and again ${sid}", argValue(s, "note"));
    }

    @Test
    void replaceInTargetMissingTargetIsNoOp() {
        HTTPSamplerProxy s = addHttp("Submit", "/submit;abcdef", "sid", "abcdef");
        injector.replaceInTarget((JMeterTreeNode) model.getRoot(), "Nope", "sid", "abcdef", "sid");
        assertEquals("/submit;abcdef", s.getPath());
        assertEquals("abcdef", argValue(s, "sid"));
    }

    // -------------------------------------------------------- createExtractor

    @Test
    void createExtractorSelectsByType() {
        assertInstanceOf(RegexExtractor.class, injector.createExtractor(approved("p", "v", "s", "var", "x=(.*)", "regex")));
        assertInstanceOf(JSONPostProcessor.class, injector.createExtractor(approved("p", "v", "s", "var", "$.p", "json")));
        assertInstanceOf(RegexExtractor.class, injector.createExtractor(approved("p", "v", "s", "var", "x=(.*)", null)),
                "unknown type falls back to regex");
    }
}
