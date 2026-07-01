package org.qainsights.jmeter.ai.agent.tool;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;
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
        assertTrue(registry.isRegistered(DeleteElementHandler.DELETE_ELEMENT));
        assertTrue(registry.size() >= 7);
    }
}
