package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
