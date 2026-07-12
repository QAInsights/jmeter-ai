package org.qainsights.jmeter.ai.agent.tool;

import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;
import org.qainsights.jmeter.ai.agent.tool.handlers.ToggleElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.UpdateElementPropertyHandler;

/**
 * Assembles the default set of agent tools (read tools plus the currently
 * implemented write tools) into a {@link ToolRegistry}. Registration order is
 * preserved, so the generated tool schema is deterministic.
 */
public final class AgentToolRegistry {

    private AgentToolRegistry() {
    }

    /** Builds a registry wired to the live JMeter tree. */
    public static ToolRegistry createDefault() {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : new ReadToolHandlers().tools()) {
            registry.register(tool);
        }
        registry.register(new AddElementHandler().tool());
        registry.register(new UpdateElementPropertyHandler().tool());
        registry.register(new DeleteElementHandler().tool());
        registry.register(new ToggleElementHandler().tool());
        registry.register(new MoveElementHandler().tool());
        return registry;
    }
}
