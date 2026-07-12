package org.qainsights.jmeter.ai.agent.loop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementAdder;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.PropertyUpdater;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;
import org.qainsights.jmeter.ai.agent.tool.handlers.UpdateElementPropertyHandler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests exercising the full {@link AgentLoop} with real
 * tool handlers (read, add, update) wired to an in-memory JMeter tree. Uses a
 * scripted {@link ChatModel} so no LLM API or live GuiPackage is required.
 * <p>
 * This is the Phase E3 lightweight harness: it validates multi-tool flows
 * (read → add → update → verify → answer), self-correction on tool errors,
 * and iteration-cap behaviour, all against real handler logic and a real
 * {@link ElementIdResolver}.
 */
class AgentE2ETest {

    // ── Scripted ChatModel ───────────────────────────────────────────────────

    /**
     * Replays a queue of scripted {@link AssistantTurn}s. Captures all tool
     * outcomes fed back via {@code next()} so tests can assert what the model
     * "saw" after each tool call.
     */
    private static final class ScriptedChatModel implements ChatModel {
        final Deque<AssistantTurn> turns = new ArrayDeque<>();
        final List<List<ToolOutcome>> receivedOutcomes = new ArrayList<>();

        @Override
        public AssistantTurn start(String userMessage) {
            return turns.removeFirst();
        }

        @Override
        public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
            receivedOutcomes.add(toolOutcomes);
            return turns.removeFirst();
        }
    }

    // ── Fake seams ───────────────────────────────────────────────────────────

    /**
     * Fake {@link ElementAdder} that creates a real {@link JMeterTreeNode} and
     * attaches it to the parent, so the {@link ElementIdResolver} can resolve
     * the new child's id in subsequent tool calls.
     */
    private static final class InMemoryAdder implements ElementAdder {
        JMeterTreeNode lastParent;
        String lastAlias;
        String lastName;
        boolean succeed = true;

        @Override
        public JMeterTreeNode add(JMeterTreeNode parent, String addAlias, String name) {
            this.lastParent = parent;
            this.lastAlias = addAlias;
            this.lastName = name;
            if (!succeed) {
                return null;
            }
            String childName = (name == null || name.isEmpty()) ? addAlias : name;
            JMeterTreeNode child = node(childName);
            parent.add(child);
            return child;
        }
    }

    /**
     * Fake {@link PropertyUpdater} that records the last call and optionally
     * sets a real property on the element so {@code get_element_config} can
     * verify it.
     */
    private static final class RecordingUpdater implements PropertyUpdater {
        JMeterTreeNode lastNode;
        String lastProperty;
        String lastValue;
        boolean succeed = true;

        @Override
        public boolean update(JMeterTreeNode node, String property, String value) {
            this.lastNode = node;
            this.lastProperty = property;
            this.lastValue = value;
            return succeed;
        }
    }

    // ── Tree helpers ─────────────────────────────────────────────────────────

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    /**
     * Builds a tree mirroring JMeter's structure: an internal wrapper root
     * whose first child is the real Test Plan.
     */
    private static JMeterTreeNode buildTree(String... planChildren) {
        JMeterTreeNode testPlan = node("Test Plan");
        for (String name : planChildren) {
            testPlan.add(node(name));
        }
        JMeterTreeNode wrapper = new JMeterTreeNode();
        wrapper.add(testPlan);
        return wrapper;
    }

    /**
     * Returns the Test Plan node (first child of the wrapper root).
     */
    private static JMeterTreeNode testPlanOf(JMeterTreeNode wrapper) {
        return (JMeterTreeNode) wrapper.getChildAt(0);
    }

    // ── Tool-argument helpers ────────────────────────────────────────────────

    private static Map<String, Object> getTreeStateArgs(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("depth", depth);
        return m;
    }

    private static Map<String, Object> getElementConfigArgs(String elementId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("element_id", elementId);
        return m;
    }

    private static Map<String, Object> addElementArgs(String type, String parentId, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("element_type", type);
        m.put("parent_id", parentId);
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> updatePropertyArgs(String elementId, String property, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("element_id", elementId);
        m.put("property", property);
        m.put("value", value);
        return m;
    }

    private static AssistantTurn toolTurn(String callId, String toolName, Map<String, Object> args) {
        return new AssistantTurn("", Collections.singletonList(
                new AssistantTurn.ToolCall(callId, toolName, args)));
    }

    private static AssistantTurn textTurn(String text) {
        return new AssistantTurn(text, Collections.emptyList());
    }

    // ── Test fixture ─────────────────────────────────────────────────────────

    private JMeterTreeNode wrapperRoot;
    private JMeterTreeNode testPlan;
    private InMemoryAdder adder;
    private RecordingUpdater updater;
    private ToolRegistry registry;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        wrapperRoot = buildTree("Thread Group");
        testPlan = testPlanOf(wrapperRoot);
        adder = new InMemoryAdder();
        updater = new RecordingUpdater();
        registry = new ToolRegistry();

        ReadToolHandlers readHandlers = new ReadToolHandlers(
                () -> wrapperRoot, new ElementIdResolver(), new SchemaGrounding());
        for (Tool tool : readHandlers.tools()) {
            registry.register(tool);
        }
        registry.register(new AddElementHandler(
                () -> wrapperRoot, new ElementIdResolver(), new SchemaGrounding(), adder).tool());
        registry.register(new UpdateElementPropertyHandler(
                () -> wrapperRoot, new ElementIdResolver(), updater).tool());

        executor = new ToolExecutor(registry);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void flow_addElementThenUpdateProperty_completesWithFinalAnswer() {
        ScriptedChatModel model = new ScriptedChatModel();
        // Turn 1: agent reads the tree
        model.turns.add(toolTurn("tu_1", "get_tree_state", getTreeStateArgs(2)));
        // Turn 2: agent adds an HTTP Request under the Thread Group
        model.turns.add(toolTurn("tu_2", "add_element",
                addElementArgs("HTTPSamplerProxy", "Test Plan/Thread Group", "Login Request")));
        // Turn 3: agent sets the path property on the new element
        model.turns.add(toolTurn("tu_3", "update_element_property",
                updatePropertyArgs("Test Plan/Thread Group/Login Request", "HTTPSampler.path", "/login")));
        // Turn 4: agent verifies the config
        model.turns.add(toolTurn("tu_4", "get_element_config",
                getElementConfigArgs("Test Plan/Thread Group/Login Request")));
        // Turn 5: agent responds with a natural-language summary
        model.turns.add(textTurn("I've added an HTTP Request named 'Login Request' under the Thread Group "
                + "and set its path to /login."));

        List<String> progress = new ArrayList<>();
        AgentLoop.AgentResult result = new AgentLoop(model, executor, 8).run(
                "Add an HTTP Request under the Thread Group and set its path to /login", progress::add);

        assertTrue(result.isCompleted());
        assertEquals(5, result.getIterations());
        assertTrue(result.getFinalText().contains("Login Request"));

        // The adder was called with the right parent, alias, and name
        assertEquals("Test Plan/Thread Group", new ElementIdResolver().idOf(adder.lastParent));
        assertEquals("httpsampler", adder.lastAlias);
        assertEquals("Login Request", adder.lastName);

        // The updater was called with the right property and value
        assertEquals("HTTPSampler.path", updater.lastProperty);
        assertEquals("/login", updater.lastValue);

        // The tree was actually mutated: Thread Group now has 1 child (the new HTTP Request)
        JMeterTreeNode threadGroup = (JMeterTreeNode) testPlan.getChildAt(0);
        assertEquals(1, threadGroup.getChildCount());
        assertEquals("Login Request", ((JMeterTreeNode) threadGroup.getChildAt(0)).getName());

        // The model received 4 tool-outcome batches (turns 1-4 each had tool calls)
        assertEquals(4, model.receivedOutcomes.size());

        // Progress lines include tool-call indicators
        assertTrue(progress.stream().anyMatch(l -> l.contains("get_tree_state")));
        assertTrue(progress.stream().anyMatch(l -> l.contains("add_element")));
        assertTrue(progress.stream().anyMatch(l -> l.contains("update_element_property")));
    }

    @Test
    void flow_getTreeStateOnly_completesInTwoIterations() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.turns.add(toolTurn("tu_1", "get_tree_state", getTreeStateArgs(2)));
        model.turns.add(textTurn("The test plan has a Thread Group with no children yet."));

        AgentLoop.AgentResult result = new AgentLoop(model, executor, 8).run(
                "Show me the test plan", null);

        assertTrue(result.isCompleted());
        assertEquals(2, result.getIterations());
        assertTrue(result.getFinalText().contains("Thread Group"));
        assertEquals(1, model.receivedOutcomes.size());
        assertFalse(model.receivedOutcomes.get(0).get(0).isError());
    }

    @Test
    void flow_selfCorrectionOnUnknownElement_retriesWithCorrectId() {
        ScriptedChatModel model = new ScriptedChatModel();
        // Turn 1: agent tries to update a non-existent element
        model.turns.add(toolTurn("tu_1", "update_element_property",
                updatePropertyArgs("Test Plan/Nonexistent", "HTTPSampler.path", "/api")));
        // Turn 2: agent reads the tree to find the correct id
        model.turns.add(toolTurn("tu_2", "get_tree_state", getTreeStateArgs(2)));
        // Turn 3: agent retries with the correct id
        model.turns.add(toolTurn("tu_3", "update_element_property",
                updatePropertyArgs("Test Plan/Thread Group", "HTTPSampler.path", "/api")));
        // Turn 4: agent responds
        model.turns.add(textTurn("I've set the path on the Thread Group."));

        List<String> progress = new ArrayList<>();
        AgentLoop.AgentResult result = new AgentLoop(model, executor, 8).run(
                "Set the path to /api on the Thread Group", progress::add);

        assertTrue(result.isCompleted());
        assertEquals(4, result.getIterations());

        // The first tool call returned an error (element not found)
        assertTrue(model.receivedOutcomes.get(0).get(0).isError());
        // The second tool call (get_tree_state) succeeded
        assertFalse(model.receivedOutcomes.get(1).get(0).isError());
        // The third tool call (retry) succeeded
        assertFalse(model.receivedOutcomes.get(2).get(0).isError());

        // The updater was eventually called with the correct values
        assertEquals("HTTPSampler.path", updater.lastProperty);
        assertEquals("/api", updater.lastValue);
    }

    @Test
    void flow_iterationCapReached_returnsExhausted() {
        ScriptedChatModel model = new ScriptedChatModel();
        // Agent keeps calling get_tree_state without ever producing a final answer
        model.turns.addAll(Arrays.asList(
                toolTurn("tu_1", "get_tree_state", getTreeStateArgs(1)),
                toolTurn("tu_2", "get_tree_state", getTreeStateArgs(1)),
                toolTurn("tu_3", "get_tree_state", getTreeStateArgs(1)),
                toolTurn("tu_4", "get_tree_state", getTreeStateArgs(1))));

        AgentLoop.AgentResult result = new AgentLoop(model, executor, 3).run(
                "keep reading the tree", null);

        assertFalse(result.isCompleted());
        assertEquals(3, result.getIterations());
    }

    @Test
    void flow_multipleToolCallsInOneTurn_allExecutedSequentially() {
        ScriptedChatModel model = new ScriptedChatModel();
        // Turn 1: agent calls two tools in a single turn
        model.turns.add(new AssistantTurn("Reading tree and config", Arrays.asList(
                new AssistantTurn.ToolCall("tu_1a", "get_tree_state", getTreeStateArgs(1)),
                new AssistantTurn.ToolCall("tu_1b", "get_element_config",
                        getElementConfigArgs("Test Plan/Thread Group")))));
        // Turn 2: agent responds
        model.turns.add(textTurn("Done inspecting."));

        AgentLoop.AgentResult result = new AgentLoop(model, executor, 8).run(
                "Show me everything", null);

        assertTrue(result.isCompleted());
        assertEquals(2, result.getIterations());
        // Both tool outcomes were fed back in a single batch
        assertEquals(1, model.receivedOutcomes.size());
        assertEquals(2, model.receivedOutcomes.get(0).size());
        assertFalse(model.receivedOutcomes.get(0).get(0).isError());
        assertFalse(model.receivedOutcomes.get(0).get(1).isError());
    }

    @Test
    void flow_addElementFails_errorFedBackToModel() {
        adder.succeed = false;

        ScriptedChatModel model = new ScriptedChatModel();
        model.turns.add(toolTurn("tu_1", "add_element",
                addElementArgs("HTTPSamplerProxy", "Test Plan/Thread Group", "Fail Request")));
        model.turns.add(textTurn("I was unable to add the element."));

        AgentLoop.AgentResult result = new AgentLoop(model, executor, 8).run(
                "Add an HTTP Request", null);

        assertTrue(result.isCompleted());
        assertEquals(2, result.getIterations());
        // The tool outcome was an error
        assertTrue(model.receivedOutcomes.get(0).get(0).isError());
        // The tree was not mutated: Thread Group still has 0 children
        JMeterTreeNode threadGroup = (JMeterTreeNode) testPlan.getChildAt(0);
        assertEquals(0, threadGroup.getChildCount());
    }
}
