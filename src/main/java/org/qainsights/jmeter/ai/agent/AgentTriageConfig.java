package org.qainsights.jmeter.ai.agent;

import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Feature flags and factory for Jev failure triage on {@code get_test_results}.
 * Independent of smart routing: triage needs only the TypeSafe master switch plus its
 * own flag, so it works whether or not request routing is enabled.
 */
public final class AgentTriageConfig {

    public static final String TRIAGE_ENABLED_KEY = "jmeter.ai.typesafe.agent.triage.enabled";
    public static final String TRIAGE_MAX_FAILURES_KEY = "jmeter.ai.typesafe.agent.triage.max.failures";
    public static final int DEFAULT_TRIAGE_MAX_FAILURES = 5;

    private AgentTriageConfig() {
    }

    /**
     * Builds a triage instance bound to the configured TypeSafe endpoint, or null when
     * the feature is off or the API key is missing/placeholder - callers treat null as
     * "return the untriaged result".
     */
    public static FailureTriage createTriage() {
        if (!enabled()) {
            return null;
        }
        String apiKey = AiConfig.getProperty(AgentRoutingConfig.API_KEY, "");
        if (!AgentRoutingConfig.usableSecret(apiKey)) {
            return null;
        }
        try {
            TypeSafeJudgmentProvider provider = new TypeSafeJudgmentProvider(apiKey,
                    AiConfig.getProperty(AgentRoutingConfig.BASE_URL_KEY,
                            TypeSafeJudgmentProvider.DEFAULT_BASE_URL),
                    AiConfig.getProperty(AgentRoutingConfig.MODEL_KEY,
                            TypeSafeJudgmentProvider.DEFAULT_MODEL),
                    AgentRoutingConfig.timeout());
            return new FailureTriage(provider, AgentRoutingConfig.minConfidence(), maxFailures());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the TypeSafe master switch and the triage flag are both on. */
    public static boolean enabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                && Boolean.parseBoolean(AiConfig.getProperty(TRIAGE_ENABLED_KEY, "false"));
    }

    /** Max unique failure signatures classified per run; invalid values use the default. */
    public static int maxFailures() {
        try {
            return Math.max(0, Integer.parseInt(AiConfig.getProperty(TRIAGE_MAX_FAILURES_KEY,
                    String.valueOf(DEFAULT_TRIAGE_MAX_FAILURES)).trim()));
        } catch (RuntimeException e) {
            return DEFAULT_TRIAGE_MAX_FAILURES;
        }
    }
}
