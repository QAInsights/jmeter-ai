package org.qainsights.jmeter.ai.record;

/**
 * Result of executing a single browser step.
 */
public record StepExecutionResult(
    BrowserStep step,
    boolean success,
    long durationMs,
    String screenshotPath,
    String error
) {}
