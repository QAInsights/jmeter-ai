package org.qainsights.jmeter.ai.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the set of {@link Tool}s available to the agent and exposes their
 * provider-neutral {@link ToolSpec}s. Registration order is preserved so the
 * generated schema is deterministic.
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * Registers a tool.
     *
     * @throws IllegalArgumentException if the tool or its name is invalid
     * @throws IllegalStateException    if a tool with the same name is already registered
     */
    public void register(Tool tool) {
        if (tool == null || tool.getSpec() == null) {
            throw new IllegalArgumentException("Tool and its spec must not be null");
        }
        String name = tool.getSpec().getName();
        if (tools.containsKey(name)) {
            throw new IllegalStateException("Tool already registered: " + name);
        }
        tools.put(name, tool);
    }

    public boolean isRegistered(String name) {
        return name != null && tools.containsKey(name);
    }

    /** Returns the tool with the given name, or {@code null} if none is registered. */
    public Tool get(String name) {
        return name == null ? null : tools.get(name);
    }

    /** Returns all registered tools in registration order. */
    public Collection<Tool> getAll() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /** Returns the specs of all registered tools in registration order. */
    public List<ToolSpec> getSpecs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools.values()) {
            specs.add(tool.getSpec());
        }
        return Collections.unmodifiableList(specs);
    }

    public int size() {
        return tools.size();
    }
}
