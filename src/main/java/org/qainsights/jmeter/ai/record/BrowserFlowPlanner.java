package org.qainsights.jmeter.ai.record;

/**
 * Interface to generate a browser flow automation plan from a user prompt.
 */
public interface BrowserFlowPlanner {
    BrowserFlowPlan plan(String prompt, SessionConfig config) throws Exception;
}
