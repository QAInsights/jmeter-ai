package org.qainsights.jmeter.ai.agent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ApplyCorrelationHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DuplicateElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.FindCorrelationCandidatesHandler;
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

public final class AgentToolPacks {

    private static final Set<String> READ = ordered(
            ReadToolHandlers.GET_TREE_STATE,
            ReadToolHandlers.GET_ELEMENT_CONFIG,
            ReadToolHandlers.GET_ELEMENT_CHILDREN,
            ReadToolHandlers.GET_ELEMENT_SCHEMA);
    private static final Map<AgentRequestRouter.Route, Set<String>> PACKS = packs();

    private AgentToolPacks() {
    }

    public static ToolRegistry select(ToolRegistry full, AgentRequestRouter.Decision decision) {
        if (full == null) {
            throw new IllegalArgumentException("full registry must not be null");
        }
        if (decision == null || !decision.isFocused()) {
            return full;
        }
        Set<String> allowed = PACKS.get(decision.route());
        if (allowed == null || !allowed.stream().allMatch(full::isRegistered)) {
            return full;
        }
        ToolRegistry selected = new ToolRegistry();
        for (Tool tool : full.getAll()) {
            if (allowed.contains(tool.getSpec().getName())) {
                selected.register(tool);
            }
        }
        return selected.size() == allowed.size() ? selected : full;
    }

    public static Set<String> toolNames(AgentRequestRouter.Route route) {
        return PACKS.getOrDefault(route, Collections.emptySet());
    }

    private static Map<AgentRequestRouter.Route, Set<String>> packs() {
        Map<AgentRequestRouter.Route, Set<String>> values = new EnumMap<>(AgentRequestRouter.Route.class);
        values.put(AgentRequestRouter.Route.INSPECT_EXPLAIN, READ);
        values.put(AgentRequestRouter.Route.EDIT_TEST_PLAN, withRead(
                AddElementHandler.ADD_ELEMENT,
                UpdateElementPropertyHandler.UPDATE_ELEMENT_PROPERTY,
                SetPropertyListHandler.SET_PROPERTY_LIST,
                SetStructuredPropertyListHandler.SET_STRUCTURED_PROPERTY_LIST,
                DeleteElementHandler.DELETE_ELEMENT,
                ToggleElementHandler.TOGGLE_ELEMENT,
                MoveElementHandler.MOVE_ELEMENT,
                DuplicateElementHandler.DUPLICATE_ELEMENT,
                RenameElementHandler.RENAME_ELEMENT,
                ReorderElementHandler.REORDER_ELEMENT));
        values.put(AgentRequestRouter.Route.RUN_DIAGNOSE, withRead(
                RunTestHandler.RUN_TEST, StopTestHandler.STOP_TEST, GetTestResultsHandler.GET_TEST_RESULTS));
        values.put(AgentRequestRouter.Route.CORRELATE, withRead(
                FindCorrelationCandidatesHandler.FIND_CORRELATION_CANDIDATES,
                ApplyCorrelationHandler.APPLY_CORRELATION));
        values.put(AgentRequestRouter.Route.PLAN_FILES, withRead(
                SavePlanHandler.SAVE_PLAN, OpenPlanHandler.OPEN_PLAN));
        return Collections.unmodifiableMap(values);
    }

    private static Set<String> withRead(String... names) {
        LinkedHashSet<String> values = new LinkedHashSet<>(READ);
        Collections.addAll(values, names);
        return Collections.unmodifiableSet(values);
    }

    private static Set<String> ordered(String... names) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Collections.addAll(values, names);
        return Collections.unmodifiableSet(values);
    }
}
