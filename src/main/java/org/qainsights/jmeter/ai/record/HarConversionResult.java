package org.qainsights.jmeter.ai.record;

/**
 * Result of converting a HAR file to JMeter format.
 */
public record HarConversionResult(
    boolean success,
    String errorMessage,
    int stepCount,
    int requestCount
) {}
