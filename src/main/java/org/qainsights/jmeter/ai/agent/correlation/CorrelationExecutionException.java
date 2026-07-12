package org.qainsights.jmeter.ai.agent.correlation;

/**
 * Thrown when the correlation probe could not be run at all (e.g. the embedded
 * engine failed to replay the plan). Wraps {@code CorrelationEngine}'s checked
 * {@code Exception} so {@code find_correlation_candidates}'s handler logic can stay
 * consistent with the rest of the agent tool layer, which reports expected failures
 * as unchecked exceptions (see {@code TestPlanRunner.TestExecutionException}).
 */
public final class CorrelationExecutionException extends RuntimeException {
    public CorrelationExecutionException(String message) {
        super(message);
    }
}
