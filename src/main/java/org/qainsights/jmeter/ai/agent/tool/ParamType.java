package org.qainsights.jmeter.ai.agent.tool;

/**
 * JSON-schema-compatible parameter types used by {@link ToolParameter}.
 * Kept provider-neutral so adapters (Claude, OpenAI, ...) can map them to their
 * own tool/function schema representations.
 */
public enum ParamType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    OBJECT,
    /** A JSON array of strings. */
    STRING_ARRAY,
    /** A JSON array of objects; each object's shape depends on the tool/property. */
    OBJECT_ARRAY
}
