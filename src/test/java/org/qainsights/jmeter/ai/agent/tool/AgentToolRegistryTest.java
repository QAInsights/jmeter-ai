package org.qainsights.jmeter.ai.agent.tool;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DuplicateElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;
import org.qainsights.jmeter.ai.agent.tool.handlers.RenameElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.RunTestHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.SetPropertyListHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.SetStructuredPropertyListHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.StopTestHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ToggleElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.UpdateElementPropertyHandler;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AgentToolRegistry}. */
class AgentToolRegistryTest {

    @Test
    void createDefault_registersReadAndWriteTools() {
        ToolRegistry registry = AgentToolRegistry.createDefault();

        assertTrue(registry.isRegistered(ReadToolHandlers.GET_TREE_STATE));
        assertTrue(registry.isRegistered(ReadToolHandlers.GET_ELEMENT_CONFIG));
        assertTrue(registry.isRegistered(ReadToolHandlers.GET_ELEMENT_SCHEMA));
        assertTrue(registry.isRegistered(AddElementHandler.ADD_ELEMENT));
        assertTrue(registry.isRegistered(UpdateElementPropertyHandler.UPDATE_ELEMENT_PROPERTY));
        assertTrue(registry.isRegistered(SetPropertyListHandler.SET_PROPERTY_LIST));
        assertTrue(registry.isRegistered(SetStructuredPropertyListHandler.SET_STRUCTURED_PROPERTY_LIST));
        assertTrue(registry.isRegistered(DeleteElementHandler.DELETE_ELEMENT));
        assertTrue(registry.isRegistered(ToggleElementHandler.TOGGLE_ELEMENT));
        assertTrue(registry.isRegistered(MoveElementHandler.MOVE_ELEMENT));
        assertTrue(registry.isRegistered(DuplicateElementHandler.DUPLICATE_ELEMENT));
        assertTrue(registry.isRegistered(RenameElementHandler.RENAME_ELEMENT));
        assertTrue(registry.isRegistered(RunTestHandler.RUN_TEST));
        assertTrue(registry.isRegistered(StopTestHandler.STOP_TEST));
        assertTrue(registry.size() >= 15);
    }
}
