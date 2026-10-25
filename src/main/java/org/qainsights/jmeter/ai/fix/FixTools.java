package org.qainsights.jmeter.ai.fix;

import java.util.Set;

import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ApplyCorrelationHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.FindCorrelationCandidatesHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;
import org.qainsights.jmeter.ai.agent.tool.handlers.SetPropertyListHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.SetStructuredPropertyListHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.UpdateElementPropertyHandler;

public final class FixTools {
    public static final Set<String> NAMES = Set.of(
            ReadToolHandlers.GET_TREE_STATE,
            ReadToolHandlers.GET_ELEMENT_CONFIG,
            ReadToolHandlers.GET_ELEMENT_CHILDREN,
            ReadToolHandlers.GET_ELEMENT_SCHEMA,
            AddElementHandler.ADD_ELEMENT,
            UpdateElementPropertyHandler.UPDATE_ELEMENT_PROPERTY,
            SetPropertyListHandler.SET_PROPERTY_LIST,
            SetStructuredPropertyListHandler.SET_STRUCTURED_PROPERTY_LIST,
            FindCorrelationCandidatesHandler.FIND_CORRELATION_CANDIDATES,
            ApplyCorrelationHandler.APPLY_CORRELATION);

    private FixTools() {
    }
}
