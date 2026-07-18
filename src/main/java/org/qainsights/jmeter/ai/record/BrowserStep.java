package org.qainsights.jmeter.ai.record;

/**
 * Represents a single step in a browser flow.
 */
public record BrowserStep(
    String action,
    String role,
    String text,
    String value,
    Integer index,
    String postcondition
) {}
