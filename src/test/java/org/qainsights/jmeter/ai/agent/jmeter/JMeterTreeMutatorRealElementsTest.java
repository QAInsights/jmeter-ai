package org.qainsights.jmeter.ai.agent.jmeter;

import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.IfControllerPanel;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.timers.gui.ConstantTimerGui;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for {@link JMeterTreeMutator#updateProperty} against real
 * JMeter elements known to store a collection-shaped property, reproducing the
 * exact corruption scenario reported against a live {@link ResponseAssertion}
 * (setting {@code Asserion.test_strings} - JMeter's own typo - replaced its
 * {@code CollectionProperty} with a {@code StringProperty} and crashed
 * {@code AssertionGui.configure()} on every subsequent open). Uses real
 * TestElement classes (no mocking of the element) so the fix is verified against
 * JMeter's actual property types, not an assumption about them.
 */
class JMeterTreeMutatorRealElementsTest {

    private final JMeterTreeMutator mutator = new JMeterTreeMutator(EdtExecutor.direct());
    private final JMeterTreeModel model = mock(JMeterTreeModel.class);

    @Test
    void blankTypedValueRemovesTheProperty() {
        ThreadGroup group = new ThreadGroup();
        group.setDelay(5L);
        JMeterTreeNode node = new JMeterTreeNode(group, null);

        assertTrue(mutator.updateProperty(model, node, ThreadGroup.DELAY, ""));

        assertInstanceOf(NullProperty.class, group.getProperty(ThreadGroup.DELAY));
    }

    @Test
    void httpTimeoutLiteralsMatchBothNativeEditors() {
        for (JMeterGUIComponent editor : List.of(new HttpTestSampleGui(), new HttpDefaultsGui())) {
            TestElement element = editor.createTestElement();
            JMeterTreeNode node = new JMeterTreeNode(element, null);
            for (String key : List.of(HTTPSamplerBase.CONNECT_TIMEOUT, HTTPSamplerBase.RESPONSE_TIMEOUT)) {
                assertTrue(mutator.updateProperty(model, node, key, "250"));
                assertInstanceOf(IntegerProperty.class, element.getProperty(key));
                assertEquals(250, element.getPropertyAsInt(key));
            }

            editor.configure(element);
            editor.modifyTestElement(element);

            assertInstanceOf(IntegerProperty.class, element.getProperty(HTTPSamplerBase.CONNECT_TIMEOUT));
            assertInstanceOf(IntegerProperty.class, element.getProperty(HTTPSamplerBase.RESPONSE_TIMEOUT));
        }
    }

    @Test
    void httpPortTextAndBlankMatchBothNativeEditors() {
        for (JMeterGUIComponent editor : List.of(new HttpTestSampleGui(), new HttpDefaultsGui())) {
            TestElement element = editor.createTestElement();
            JMeterTreeNode node = new JMeterTreeNode(element, null);
            assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.PORT, "08081"));
            assertInstanceOf(StringProperty.class, element.getProperty(HTTPSamplerBase.PORT));
            assertEquals("08081", element.getPropertyAsString(HTTPSamplerBase.PORT));

            editor.configure(element);
            editor.modifyTestElement(element);
            assertInstanceOf(StringProperty.class, element.getProperty(HTTPSamplerBase.PORT));
            assertEquals("08081", element.getPropertyAsString(HTTPSamplerBase.PORT));

            assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.PORT, ""));
            assertInstanceOf(NullProperty.class, element.getProperty(HTTPSamplerBase.PORT));
        }
    }

    @Test
    void scalarEditsRetainNativeAndExpressionTypesAfterSaveAndReload(@TempDir Path directory) throws Exception {
        Path aliases = directory.resolve("saveservice.properties");
        try (var input = getClass().getResourceAsStream("/scalar-saveservice.properties")) {
            Files.copy(input, aliases);
        }
        Path log = directory.resolve("round-trip.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // SaveService loads its aliases once per JVM; the rest of the suite runs without them.
        Process process = new ProcessBuilder(java.toString(), "-Dsaveservice_properties=" + aliases,
                "-cp", classpath, getClass().getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "JMX round trip timed out; see " + log);
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    public static void main(String[] args) throws Exception {
        JMeterUtils.loadJMeterProperties("nonexistent.properties");
        verifyScalarRoundTrip(Path.of(args[0]));
    }

    private static void verifyScalarRoundTrip(Path directory) throws Exception {
        JMeterTreeMutator mutator = new JMeterTreeMutator(EdtExecutor.direct());
        JMeterTreeModel model = new JMeterTreeModel();
        IfController controller = (IfController) new IfControllerPanel().createTestElement();
        ThreadGroup group = (ThreadGroup) new ThreadGroupGui().createTestElement();
        ConstantTimer timer = (ConstantTimer) new ConstantTimerGui().createTestElement();
        JMeterTreeNode controllerNode = new JMeterTreeNode(controller, null);
        JMeterTreeNode groupNode = new JMeterTreeNode(group, null);
        JMeterTreeNode timerNode = new JMeterTreeNode(timer, null);
        String useExpression = "IfController.useExpression";
        String evaluateAll = "IfController.evaluateAll";
        String expression = "prefix-${flag}";
        controller.setProperty(useExpression, "false");
        group.removeProperty(ThreadGroup.DURATION);
        group.setProperty(ThreadGroup.DELAY, "${oldDelay}");

        assertTrue(mutator.updateProperty(model, controllerNode, useExpression, "true"));
        controller.setEvaluateAll(true);
        assertTrue(mutator.updateProperty(model, controllerNode, evaluateAll, expression));
        assertTrue(mutator.updateProperty(model, groupNode, ThreadGroup.DURATION, "3000000000"));
        assertTrue(mutator.updateProperty(model, groupNode, ThreadGroup.DELAY, "5"));
        assertTrue(mutator.updateProperty(model, timerNode, ConstantTimer.DELAY, "0015"));

        Path saved = directory.resolve("scalars.jmx");
        HashTree tree = new ListedHashTree();
        tree.add(group).add(controller).add(timer);
        try (var output = Files.newOutputStream(saved)) {
            SaveService.saveTree(tree, output);
        }
        String xml = Files.readString(saved);
        assertTrue(xml.contains("<boolProp name=\"IfController.useExpression\">true</boolProp>"), xml);
        assertTrue(xml.contains("<longProp name=\"ThreadGroup.duration\">3000000000</longProp>"), xml);
        assertTrue(xml.contains("<longProp name=\"ThreadGroup.delay\">5</longProp>"), xml);
        assertTrue(xml.contains("<stringProp name=\"IfController.evaluateAll\">prefix-${flag}</stringProp>"), xml);
        assertTrue(xml.contains("<stringProp name=\"ConstantTimer.delay\">0015</stringProp>"), xml);

        HashTree reopened = SaveService.loadTree(saved.toFile());
        ThreadGroup reopenedGroup = (ThreadGroup) reopened.list().iterator().next();
        HashTree groupChildren = reopened.getTree(reopenedGroup);
        IfController reopenedController = (IfController) groupChildren.list().iterator().next();
        TestElement reopenedTimer = (TestElement) groupChildren.getTree(reopenedController).list().iterator().next();
        assertInstanceOf(BooleanProperty.class, reopenedController.getProperty(useExpression));
        assertTrue(reopenedController.isUseExpression());
        assertInstanceOf(StringProperty.class, reopenedController.getProperty(evaluateAll));
        assertEquals(expression, reopenedController.getPropertyAsString(evaluateAll));
        assertInstanceOf(LongProperty.class, reopenedGroup.getProperty(ThreadGroup.DURATION));
        assertInstanceOf(LongProperty.class, reopenedGroup.getProperty(ThreadGroup.DELAY));
        assertEquals(3_000_000_000L, reopenedGroup.getDuration());
        assertEquals(5, reopenedGroup.getDelay());
        assertInstanceOf(StringProperty.class, reopenedTimer.getProperty(ConstantTimer.DELAY));
        assertEquals("0015", reopenedTimer.getPropertyAsString(ConstantTimer.DELAY));
    }

    @Test
    void responseAssertion_testStrings_isRejectedAndStaysReadable() {
        ResponseAssertion assertion = new ResponseAssertion();
        JMeterTreeNode node = new JMeterTreeNode(assertion, null);

        assertFalse(mutator.updateProperty(model, node, "Asserion.test_strings", "200"));
        // The property must still be the real CollectionProperty; reading it must not throw.
        assertNotNull(assertion.getTestStrings());
        assertEquals(0, assertion.getTestStrings().size());
    }

    @Test
    void responseAssertion_scalarProperty_stillUpdates() {
        ResponseAssertion assertion = new ResponseAssertion();
        JMeterTreeNode node = new JMeterTreeNode(assertion, null);

        assertTrue(mutator.updateProperty(model, node, "Assertion.test_field", "Assertion.response_code"));
        assertEquals("Assertion.response_code", assertion.getPropertyAsString("Assertion.test_field"));
    }

    @Test
    void headerManager_headers_isRejectedAndStaysReadable() {
        HeaderManager headerManager = new HeaderManager();
        JMeterTreeNode node = new JMeterTreeNode(headerManager, null);

        assertFalse(mutator.updateProperty(model, node, "HeaderManager.headers", "not-a-header"));
        assertNotNull(headerManager.getHeaders());
        assertEquals(0, headerManager.getHeaders().size());
    }

    @Test
    void arguments_arguments_isRejectedAndStaysReadable() {
        Arguments arguments = new Arguments();
        JMeterTreeNode node = new JMeterTreeNode(arguments, null);

        assertFalse(mutator.updateProperty(model, node, "Arguments.arguments", "not-an-argument"));
        assertNotNull(arguments.getArguments());
        assertEquals(0, arguments.getArguments().size());
    }

    @Test
    void threadGroup_loopControllerLoops_delegatesToNestedLoopController() {
        ThreadGroup threadGroup = new ThreadGroup();
        LoopController loopController = new LoopController();
        loopController.setLoops(1);
        threadGroup.setSamplerController(loopController);
        JMeterTreeNode node = new JMeterTreeNode(threadGroup, null);

        assertTrue(mutator.updateProperty(model, node, "LoopController.loops", "-1"));

        assertEquals(-1, loopController.getLoops());
        assertInstanceOf(IntegerProperty.class, loopController.getProperty(LoopController.LOOPS));
        // The write must not have created a decoy top-level property on the ThreadGroup itself.
        assertTrue(threadGroup.getProperty("LoopController.loops") instanceof NullProperty);
    }

    @Test
    void finiteLoopCountIsWrittenAsTextLikeTheNativeEditor() {
        LoopControlPanel editor = new LoopControlPanel();
        LoopController loopController = (LoopController) editor.createTestElement();
        JMeterTreeNode node = new JMeterTreeNode(loopController, null);

        assertTrue(mutator.updateProperty(model, node, LoopController.LOOPS, "5"));
        assertInstanceOf(StringProperty.class, loopController.getProperty(LoopController.LOOPS));

        editor.configure(loopController);
        editor.modifyTestElement(loopController);
        assertInstanceOf(StringProperty.class, loopController.getProperty(LoopController.LOOPS));
        assertEquals(5, loopController.getLoops());

        assertTrue(mutator.updateProperty(model, node, LoopController.LOOPS, ""));
        assertInstanceOf(IntegerProperty.class, loopController.getProperty(LoopController.LOOPS));
        assertEquals(LoopController.INFINITE_LOOP_COUNT, loopController.getLoops());
    }

    @Test
    void duplicateElement_insertsCloneAsNextSiblingUnderSameParent() {
        JMeterTreeModel realModel = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) realModel.getRoot();
        JMeterTreeNode parent = new JMeterTreeNode(new ThreadGroup(), null);
        ConfigTestElement originalElement = new ConfigTestElement();
        originalElement.setName("Original");
        JMeterTreeNode original = new JMeterTreeNode(originalElement, null);
        JMeterTreeNode sibling = node("Sibling");
        realModel.insertNodeInto(parent, root, 0);
        realModel.insertNodeInto(original, parent, 0);
        realModel.insertNodeInto(sibling, parent, 1);

        JMeterTreeNode duplicate = mutator.duplicateElement(realModel, original);

        assertNotNull(duplicate);
        assertEquals("Original", duplicate.getName());
        assertNotSame(original.getTestElement(), duplicate.getTestElement());
        assertEquals(3, parent.getChildCount());
        assertSame(parent, duplicate.getParent());
        assertEquals(1, parent.getIndex(duplicate));
    }

    @Test
    void duplicateElement_cannotDuplicateTreeRoot() {
        JMeterTreeModel realModel = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) realModel.getRoot();

        assertNull(mutator.duplicateElement(realModel, root));
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @Test
    void responseAssertion_replacePropertyList_populatesPatterns() {
        ResponseAssertion assertion = new ResponseAssertion();
        JMeterTreeNode node = new JMeterTreeNode(assertion, null);

        assertTrue(mutator.replacePropertyList(model, node, "Asserion.test_strings",
                java.util.Collections.singletonList("200")));

        assertEquals(1, assertion.getTestStrings().size());
        assertEquals("200", assertion.getTestStrings().get(0).getStringValue());
    }

    @Test
    void responseAssertion_replacePropertyList_calledTwice_replacesRatherThanAppends() {
        ResponseAssertion assertion = new ResponseAssertion();
        JMeterTreeNode node = new JMeterTreeNode(assertion, null);

        mutator.replacePropertyList(model, node, "Asserion.test_strings",
                java.util.Collections.singletonList("200"));
        mutator.replacePropertyList(model, node, "Asserion.test_strings",
                java.util.Arrays.asList("404", "500"));

        assertEquals(2, assertion.getTestStrings().size());
        assertEquals("404", assertion.getTestStrings().get(0).getStringValue());
        assertEquals("500", assertion.getTestStrings().get(1).getStringValue());
    }

    @Test
    void headerManager_replaceStructuredPropertyList_populatesHeaders() {
        HeaderManager headerManager = new HeaderManager();
        JMeterTreeNode node = new JMeterTreeNode(headerManager, null);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("name", "Content-Type");
        entry.put("value", "application/json");

        assertTrue(mutator.replaceStructuredPropertyList(model, node, HeaderManager.HEADERS,
                java.util.Collections.singletonList(entry)));

        assertEquals(1, headerManager.getHeaders().size());
        Header header = (Header) headerManager.getHeaders().get(0).getObjectValue();
        assertEquals("Content-Type", header.getName());
        assertEquals("application/json", header.getValue());
    }

    @Test
    void headerManager_replaceStructuredPropertyList_calledTwice_replacesRatherThanAppends() {
        HeaderManager headerManager = new HeaderManager();
        JMeterTreeNode node = new JMeterTreeNode(headerManager, null);

        mutator.replaceStructuredPropertyList(model, node, HeaderManager.HEADERS,
                java.util.Collections.singletonList(nameValue("A", "1")));
        mutator.replaceStructuredPropertyList(model, node, HeaderManager.HEADERS,
                java.util.Collections.singletonList(nameValue("B", "2")));

        assertEquals(1, headerManager.getHeaders().size());
        Header header = (Header) headerManager.getHeaders().get(0).getObjectValue();
        assertEquals("B", header.getName());
    }

    @Test
    void arguments_replaceStructuredPropertyList_populatesArguments() {
        Arguments arguments = new Arguments();
        JMeterTreeNode node = new JMeterTreeNode(arguments, null);

        assertTrue(mutator.replaceStructuredPropertyList(model, node, Arguments.ARGUMENTS,
                java.util.Collections.singletonList(nameValue("userId", "123"))));

        assertEquals(1, arguments.getArguments().size());
        org.apache.jmeter.config.Argument arg =
                (org.apache.jmeter.config.Argument) arguments.getArguments().get(0).getObjectValue();
        assertEquals("userId", arg.getName());
        assertEquals("123", arg.getValue());
    }

    @Test
    void authManager_replaceStructuredPropertyList_populatesAuthorization() {
        AuthManager authManager = new AuthManager();
        JMeterTreeNode node = new JMeterTreeNode(authManager, null);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("url", "https://example.com");
        entry.put("username", "bob");
        entry.put("password", "secret");
        entry.put("domain", "");
        entry.put("realm", "");
        entry.put("mechanism", "DIGEST");

        assertTrue(mutator.replaceStructuredPropertyList(model, node, "AuthManager.auth_list",
                java.util.Collections.singletonList(entry)));

        assertEquals(1, authManager.getAuthObjects().size());
        Authorization auth = (Authorization) authManager.getAuthObjects().get(0).getObjectValue();
        assertEquals("https://example.com", auth.getURL());
        assertEquals("bob", auth.getUser());
        assertEquals("secret", auth.getPass());
        assertEquals(AuthManager.Mechanism.DIGEST, auth.getMechanism());
    }

    @Test
    void authManager_replaceStructuredPropertyList_invalidMechanism_isRejectedAndStaysReadable() {
        AuthManager authManager = new AuthManager();
        JMeterTreeNode node = new JMeterTreeNode(authManager, null);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("url", "https://example.com");
        entry.put("mechanism", "not-a-real-mechanism");

        assertFalse(mutator.replaceStructuredPropertyList(model, node, "AuthManager.auth_list",
                java.util.Collections.singletonList(entry)));

        assertNotNull(authManager.getAuthObjects());
        assertEquals(0, authManager.getAuthObjects().size());
    }

    @Test
    void httpSamplerProxy_replaceStructuredPropertyList_populatesArgumentsAsRealHttpArgument() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("name", "newOrder");
        entry.put("value", "confirmed");

        assertTrue(mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                java.util.Collections.singletonList(entry)));

        assertEquals(1, sampler.getArguments().getArguments().size());
        Object stored = sampler.getArguments().getArguments().get(0).getObjectValue();
        // Must be a real HTTPArgument (not a plain Argument): HTTPSamplerBase.getQueryString()
        // casts each entry to HTTPArgument when actually running the sampler.
        assertInstanceOf(HTTPArgument.class, stored);
        HTTPArgument arg = (HTTPArgument) stored;
        assertEquals("newOrder", arg.getName());
        assertEquals("confirmed", arg.getValue());
        assertTrue(arg.isAlwaysEncoded());
        assertEquals("newOrder=confirmed", sampler.getQueryString("UTF-8"));
    }

    @Test
    void httpSamplerProxy_replaceStructuredPropertyList_calledTwice_replacesRatherThanAppends() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);

        mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                java.util.Collections.singletonList(nameValue("A", "1")));
        mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                java.util.Collections.singletonList(nameValue("B", "2")));

        assertEquals(1, sampler.getArguments().getArguments().size());
        HTTPArgument arg = (HTTPArgument) sampler.getArguments().getArguments().get(0).getObjectValue();
        assertEquals("B", arg.getName());
    }

    @Test
    void httpSamplerProxy_rawModeAfterArguments_updatesEncodingInBothDirections() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);
        assertTrue(mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                List.of(nameValue("item", "A+B %"))));
        HTTPArgument argument = (HTTPArgument) sampler.getArguments().getArgument(0);
        assertTrue(argument.isAlwaysEncoded());
        assertEquals("item=A%2BB+%25", sampler.getQueryString("UTF-8"));

        assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.POST_BODY_RAW, "true"));
        assertTrue(sampler.getPostBodyRaw());
        assertFalse(argument.isAlwaysEncoded());

        assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.POST_BODY_RAW, "false"));
        assertFalse(sampler.getPostBodyRaw());
        assertTrue(argument.isAlwaysEncoded());
        assertEquals("item=A%2BB+%25", sampler.getQueryString("UTF-8"));
    }

    @Test
    void httpSamplerProxy_clearingRawMode_keepsUnnamedBodyUnencoded() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);
        String json = "{\"item\":\"A+B %\"}";
        assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.POST_BODY_RAW, "true"));
        assertTrue(mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                List.of(nameValue("", json))));

        assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.POST_BODY_RAW, "false"));

        assertFalse(sampler.getPostBodyRaw());
        assertTrue(sampler.getSendParameterValuesAsPostBody());
        HTTPArgument argument = (HTTPArgument) sampler.getArguments().getArgument(0);
        assertFalse(argument.isAlwaysEncoded());
        assertEquals(json, argument.getEncodedValue());
    }

    @Test
    void httpSamplerProxy_replacingArgumentsAfterClearingRawMode_keepsUnnamedBodyUnencoded() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);
        assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.POST_BODY_RAW, "true"));
        assertTrue(mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                List.of(nameValue("", "{\"count\":1}"))));
        assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.POST_BODY_RAW, "false"));

        String json = "{\"item\":\"A+B %\"}";
        assertTrue(mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                List.of(nameValue("", json))));

        assertFalse(sampler.getPostBodyRaw());
        assertTrue(sampler.getSendParameterValuesAsPostBody());
        HTTPArgument argument = (HTTPArgument) sampler.getArguments().getArgument(0);
        assertFalse(argument.isAlwaysEncoded());
        assertEquals(json, argument.getEncodedValue());
    }

    @Test
    void httpSamplerProxy_editingExistingRawSampler_disablesArgumentEncoding() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setPostBodyRaw(true);
        HTTPArgument argument = new HTTPArgument("", "{\"count\":1}");
        sampler.getArguments().addArgument(argument);
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);
        assertTrue(argument.isAlwaysEncoded());

        assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.PATH, "/cart"));

        assertFalse(argument.isAlwaysEncoded());
        assertEquals("{\"count\":1}", argument.getValue());
    }

    @Test
    void httpSamplerProxy_rawModeBeforeArguments_sendsLiteralBody() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<byte[]> received = new AtomicReference<>();
        server.createContext("/cart", exchange -> {
            try {
                received.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setImplementation("HttpClient4");
            sampler.setDomain("127.0.0.1");
            sampler.setPort(server.getAddress().getPort());
            sampler.setPath("/cart");
            sampler.setMethod("POST");
            sampler.setConnectTimeout("5000");
            sampler.setResponseTimeout("5000");
            JMeterTreeNode node = new JMeterTreeNode(sampler, null);
            String json = "{\"item\":\"A+B %\"}";

            assertTrue(mutator.updateProperty(model, node, HTTPSamplerBase.POST_BODY_RAW, "true"));
            assertTrue(mutator.replaceStructuredPropertyList(model, node, HTTPSamplerBase.ARGUMENTS,
                    List.of(nameValue("", json))));

            assertFalse(((HTTPArgument) sampler.getArguments().getArgument(0)).isAlwaysEncoded());
            assertEquals("200", sampler.sample().getResponseCode());
            assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), received.get());
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> nameValue(String name, String value) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }
}
