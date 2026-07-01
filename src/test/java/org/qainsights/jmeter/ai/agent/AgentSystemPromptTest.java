package org.qainsights.jmeter.ai.agent;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AgentSystemPrompt}. */
class AgentSystemPromptTest {

    @Test
    void build_includesToolGuidanceAndHierarchy() {
        String prompt = AgentSystemPrompt.build(new SchemaGrounding());

        assertTrue(prompt.contains("get_tree_state"));
        assertTrue(prompt.contains("add_element"));
        assertTrue(prompt.contains("update_element_property"));
        // The level-1 hierarchy is appended verbatim from SchemaGrounding.
        assertTrue(prompt.contains(new SchemaGrounding().hierarchySummary()));
    }
}
