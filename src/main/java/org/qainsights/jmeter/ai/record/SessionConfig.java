package org.qainsights.jmeter.ai.record;

/**
 * Configuration parameters for a recording session.
 */
public record SessionConfig(
    String prompt,
    String baseUri,
    String browser
) {}
