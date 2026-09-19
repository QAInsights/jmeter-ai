package org.qainsights.jmeter.ai.agent;

import java.time.Duration;

import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;

public final class AgentRoutingConfig {

    public static final String TYPESAFE_ENABLED_KEY = "jmeter.ai.typesafe.enabled";
    public static final String ROUTING_ENABLED_KEY = "jmeter.ai.typesafe.agent.routing.enabled";
    public static final String MIN_CONFIDENCE_KEY = "jmeter.ai.typesafe.agent.routing.min.confidence";
    public static final String EXPANSION_ENABLED_KEY = "jmeter.ai.typesafe.agent.routing.expansion.enabled";
    public static final String EXPANSION_MAX_KEY = "jmeter.ai.typesafe.agent.routing.expansion.max";
    public static final int DEFAULT_EXPANSION_MAX = 1;
    public static final String API_KEY = "typesafe.api.key";
    public static final String BASE_URL_KEY = "typesafe.base.url";
    public static final String MODEL_KEY = "typesafe.model";
    public static final String TIMEOUT_SECONDS_KEY = "typesafe.timeout.seconds";
    public static final double DEFAULT_MIN_CONFIDENCE = 0.75;

    private AgentRoutingConfig() {
    }

    public static AgentRequestRouter createRouter() {
        if (!enabled()) {
            return null;
        }
        String apiKey = AiConfig.getProperty(API_KEY, "");
        if (!usableSecret(apiKey)) {
            return message -> AgentRequestRouter.Decision.unavailable();
        }
        try {
            TypeSafeJudgmentProvider provider = new TypeSafeJudgmentProvider(apiKey,
                    AiConfig.getProperty(BASE_URL_KEY, TypeSafeJudgmentProvider.DEFAULT_BASE_URL),
                    AiConfig.getProperty(MODEL_KEY, TypeSafeJudgmentProvider.DEFAULT_MODEL), timeout());
            return new TypeSafeAgentRequestRouter(provider, minConfidence());
        } catch (RuntimeException e) {
            return message -> AgentRequestRouter.Decision.unavailable();
        }
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(TYPESAFE_ENABLED_KEY, "false"))
                && Boolean.parseBoolean(AiConfig.getProperty(ROUTING_ENABLED_KEY, "false"));
    }

    /** True when a focused run may offer the {@code expand_tools} escape hatch. */
    public static boolean expansionEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(EXPANSION_ENABLED_KEY, "false"));
    }

    /** Max tool-set expansions per run; invalid values fall back to the default. */
    public static int expansionMax() {
        try {
            return Math.max(0, Integer.parseInt(AiConfig.getProperty(EXPANSION_MAX_KEY,
                    String.valueOf(DEFAULT_EXPANSION_MAX)).trim()));
        } catch (RuntimeException e) {
            return DEFAULT_EXPANSION_MAX;
        }
    }

    public static double minConfidence() {
        try {
            double value = Double.parseDouble(AiConfig.getProperty(MIN_CONFIDENCE_KEY,
                    String.valueOf(DEFAULT_MIN_CONFIDENCE)).trim());
            return Double.isFinite(value) && value >= 0 && value <= 1 ? value : DEFAULT_MIN_CONFIDENCE;
        } catch (RuntimeException e) {
            return DEFAULT_MIN_CONFIDENCE;
        }
    }

    public static Duration timeout() {
        try {
            long seconds = Long.parseLong(AiConfig.getProperty(TIMEOUT_SECONDS_KEY,
                    String.valueOf(TypeSafeJudgmentProvider.DEFAULT_TIMEOUT.toSeconds())).trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : TypeSafeJudgmentProvider.DEFAULT_TIMEOUT;
        } catch (RuntimeException e) {
            return TypeSafeJudgmentProvider.DEFAULT_TIMEOUT;
        }
    }

    static boolean usableSecret(String value) {
        return value != null && !value.trim().isEmpty()
                && !value.trim().regionMatches(true, 0, "YOUR_", 0, 5);
    }
}
