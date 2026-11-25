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

    @Test
    void build_includesCompletionAndRampUpRules() {
        String prompt = AgentSystemPrompt.build(new SchemaGrounding());

        assertTrue(prompt.contains("Never reply with raw JMX/XML"));
        assertTrue(prompt.contains("call get_tree_state and confirm"));
        assertTrue(prompt.contains("save_plan returned success"));
        assertTrue(prompt.contains("ThreadGroup.ramp_time"));
        assertTrue(prompt.contains("set ramp_time to 0"));
        assertTrue(prompt.contains("13. When the request is fully satisfied"));
    }
}
