package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

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
        // The write must not have created a decoy top-level property on the ThreadGroup itself.
        assertTrue(threadGroup.getProperty("LoopController.loops") instanceof NullProperty);
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

    private static Map<String, String> nameValue(String name, String value) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }
}
