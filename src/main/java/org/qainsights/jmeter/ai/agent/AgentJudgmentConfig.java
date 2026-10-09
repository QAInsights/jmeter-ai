package org.qainsights.jmeter.ai.agent;

import java.util.Locale;

import org.qainsights.jmeter.ai.service.JudgmentProvider;
import org.qainsights.jmeter.ai.service.OpenAiDecisionsJudgmentProvider;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Picks the backend behind smart routing and failure triage: TypeSafe Jev (default) or the
 * OpenAI Decisions API. The routing and triage feature flags are shared by both backends.
 */
public final class AgentJudgmentConfig {
    public static final String PROVIDER_KEY = "jmeter.ai.judgment.provider";
    public static final String TYPESAFE = "typesafe";
    public static final String OPENAI = "openai";
    public static final String OPENAI_API_KEY = "openai.api.key";
    public static final String OPENAI_MODEL_KEY = "openai.decisions.model";

    private AgentJudgmentConfig() {
    }

    /** The configured backend; anything other than {@code openai} means TypeSafe Jev. */
    public static String provider() {
        String value = AiConfig.getProperty(PROVIDER_KEY, TYPESAFE);
        return value != null && OPENAI.equals(value.trim().toLowerCase(Locale.ROOT)) ? OPENAI : TYPESAFE;
    }

    /** Name shown on routing and triage cards. */
    public static String brand() {
        try {
            return OPENAI.equals(provider()) ? "OpenAI Decisions" : "Jev";
        } catch (RuntimeException e) {
            return "Jev";
        }
    }

    /**
     * Builds the configured judgment provider, or null when its API key is missing or a
     * placeholder. May throw when the client cannot be constructed (for example a bad URL).
     */
    static JudgmentProvider createProvider() {
        if (OPENAI.equals(provider())) {
            String apiKey = AiConfig.getProperty(OPENAI_API_KEY, "");
            if (!AgentRoutingConfig.usableSecret(apiKey)) {
                return null;
            }
            return new OpenAiDecisionsJudgmentProvider(apiKey.trim(),
                    AiConfig.getProperty(OPENAI_MODEL_KEY, OpenAiDecisionsJudgmentProvider.DEFAULT_MODEL),
                    AgentRoutingConfig.timeout());
        }
        String apiKey = AiConfig.getProperty(AgentRoutingConfig.API_KEY, "");
        if (!AgentRoutingConfig.usableSecret(apiKey)) {
            return null;
        }
        return new TypeSafeJudgmentProvider(apiKey,
                AiConfig.getProperty(AgentRoutingConfig.BASE_URL_KEY, TypeSafeJudgmentProvider.DEFAULT_BASE_URL),
                AiConfig.getProperty(AgentRoutingConfig.MODEL_KEY, TypeSafeJudgmentProvider.DEFAULT_MODEL),
                AgentRoutingConfig.timeout());
    }
}
