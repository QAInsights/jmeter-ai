package org.qainsights.jmeter.ai.fix;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FixToolsTest {
    @Test
    void everyFixToolNameIsRegisteredInTheDefaultRegistry() {
        var registry = AgentToolRegistry.createDefault();

        assertTrue(FixTools.NAMES.stream().allMatch(registry::isRegistered));
    }
}
