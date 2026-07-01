package org.qainsights.jmeter.ai.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, provider-neutral definition of a tool: its name, description,
 * parameters and (optional) human-readable preconditions. Adapters translate a
 * {@code ToolSpec} into a specific LLM provider's tool/function schema.
 */
public final class ToolSpec {

    private final String name;
    private final String description;
    private final List<ToolParameter> parameters;
    private final List<String> preconditions;

    private ToolSpec(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(b.parameters));
        this.preconditions = Collections.unmodifiableList(new ArrayList<>(b.preconditions));
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<ToolParameter> getParameters() {
        return parameters;
    }

    public List<String> getPreconditions() {
        return preconditions;
    }

    /** Returns only the parameters flagged as required. */
    public List<ToolParameter> getRequiredParameters() {
        List<ToolParameter> required = new ArrayList<>();
        for (ToolParameter p : parameters) {
            if (p.isRequired()) {
                required.add(p);
            }
        }
        return Collections.unmodifiableList(required);
    }

    /** Builder for {@link ToolSpec}. */
    public static final class Builder {
        private final String name;
        private String description = "";
        private final List<ToolParameter> parameters = new ArrayList<>();
        private final List<String> preconditions = new ArrayList<>();

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Tool name must not be blank");
            }
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder addParameter(ToolParameter parameter) {
            if (parameter == null) {
                throw new IllegalArgumentException("Parameter must not be null");
            }
            this.parameters.add(parameter);
            return this;
        }

        public Builder addPrecondition(String precondition) {
            if (precondition != null && !precondition.trim().isEmpty()) {
                this.preconditions.add(precondition);
            }
            return this;
        }

        public ToolSpec build() {
            return new ToolSpec(this);
        }
    }
}
