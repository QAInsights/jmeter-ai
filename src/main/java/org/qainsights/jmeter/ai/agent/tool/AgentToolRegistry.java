package org.qainsights.jmeter.ai.agent.tool;

import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DuplicateElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.GetTestResultsHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.OpenPlanHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;
import org.qainsights.jmeter.ai.agent.tool.handlers.RenameElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReorderElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.RunTestHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.SavePlanHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.SetPropertyListHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.SetStructuredPropertyListHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.StopTestHandler;
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
        registry.register(new SetPropertyListHandler().tool());
        registry.register(new SetStructuredPropertyListHandler().tool());
        registry.register(new DeleteElementHandler().tool());
        registry.register(new ToggleElementHandler().tool());
        registry.register(new MoveElementHandler().tool());
        registry.register(new DuplicateElementHandler().tool());
        registry.register(new RenameElementHandler().tool());
        registry.register(new ReorderElementHandler().tool());
        registry.register(new RunTestHandler().tool());
        registry.register(new StopTestHandler().tool());
        registry.register(new GetTestResultsHandler().tool());
        registry.register(new SavePlanHandler().tool());
        registry.register(new OpenPlanHandler().tool());
        return registry;
    }
}
