package org.qainsights.jmeter.ai.agent.tool;

import java.util.Collections;
import java.util.List;

/**
 * Immutable, provider-neutral description of a single tool parameter.
 * Built via {@link #builder(String, ParamType)}.
 */
public final class ToolParameter {

    private final String name;
    private final ParamType type;
    private final String description;
    private final boolean required;
    private final List<String> enumValues;

    private ToolParameter(Builder b) {
        this.name = b.name;
        this.type = b.type;
        this.description = b.description;
        this.required = b.required;
        this.enumValues = b.enumValues;
    }

    public static Builder builder(String name, ParamType type) {
        return new Builder(name, type);
    }

    public String getName() {
        return name;
    }

    public ParamType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequired() {
        return required;
    }

    /** Allowed values for an enumerated parameter, or an empty list if unconstrained. */
    public List<String> getEnumValues() {
        return enumValues;
    }

    /** Builder for {@link ToolParameter}. */
    public static final class Builder {
        private final String name;
        private final ParamType type;
        private String description = "";
        private boolean required;
        private List<String> enumValues = Collections.emptyList();

        private Builder(String name, ParamType type) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Parameter name must not be blank");
            }
            if (type == null) {
                throw new IllegalArgumentException("Parameter type must not be null");
            }
            this.name = name;
            this.type = type;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder enumValues(List<String> enumValues) {
            this.enumValues = enumValues == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<>(enumValues));
            return this;
        }

        public ToolParameter build() {
            return new ToolParameter(this);
        }
    }
}
