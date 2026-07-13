package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GuiElementAdder}. The GUI/static seams are supplied as
 * in-memory fakes and the adder runs synchronously via {@link EdtExecutor#direct()},
 * so no live JMeter GUI or static mocking is required.
 */
class GuiElementAdderTest {

    /** Records selections and toggles availability. */
    private static final class FakeSelector implements GuiElementAdder.Selector {
        boolean available = true;
        JMeterTreeNode selected;

        @Override
        public boolean select(JMeterTreeNode parent) {
            if (!available) {
                return false;
            }
            this.selected = parent;
            return true;
        }
    }

    /** Records create calls and optionally attaches a real child to the parent. */
    private static final class FakeCreator implements GuiElementAdder.Creator {
        final List<String> calls = new ArrayList<>();
        boolean succeed = true;
        JMeterTreeNode parentToPopulate;

        @Override
        public boolean create(String addAlias, String name) {
            calls.add(addAlias + ":" + name);
            if (succeed && parentToPopulate != null) {
                parentToPopulate.add(node(name == null ? addAlias : name));
            }
            return succeed;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private FakeSelector selector;
    private FakeCreator creator;
    private GuiElementAdder adder;
    private JMeterTreeNode parent;

    @BeforeEach
    void setUp() {
        selector = new FakeSelector();
        creator = new FakeCreator();
        adder = new GuiElementAdder(EdtExecutor.direct(), selector, creator);
        parent = node("Thread Group");
        creator.parentToPopulate = parent;
    }

    @Test
    void add_nullParentOrAlias_returnsNull() {
        assertNull(adder.add(null, "httpsampler", "X"));
        assertNull(adder.add(parent, null, "X"));
    }

    @Test
    void add_selectsParentDelegatesAndReturnsNewChild() {
        JMeterTreeNode result = adder.add(parent, "httpsampler", "Login");

        assertNotNull(result);
        assertSame(parent, selector.selected);
        assertEquals("Login", result.getName());
        assertEquals(1, creator.calls.size());
        assertEquals("httpsampler:Login", creator.calls.get(0));
    }

    @Test
    void add_whenCreateFails_returnsNull() {
        creator.succeed = false;
        assertNull(adder.add(parent, "httpsampler", "Login"));
    }

    @Test
    void add_whenGuiUnavailable_returnsNullAndDoesNotCreate() {
        selector.available = false;
        assertNull(adder.add(parent, "httpsampler", "Login"));
        assertTrue(creator.calls.isEmpty());
    }
}
