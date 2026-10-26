package org.qainsights.jmeter.ai.load;

/** The variable name and maximum connection count of an enabled JDBC pool. */
public record JdbcPool(String variableName, int poolMax) {
}
