package org.qainsights.jmeter.ai.record;

import java.util.List;

/**
 * Plan containing a list of steps to execute in the browser.
 */
public record BrowserFlowPlan(
    List<BrowserStep> steps,
    String description
) {}
