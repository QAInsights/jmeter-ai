package org.qainsights.jmeter.ai.agent;

import java.util.Locale;

import org.qainsights.jmeter.ai.service.JudgmentProvider;
import org.qainsights.jmeter.ai.service.OpenAiDecisionsJudgmentProvider;
import org.qainsights.jmeter.ai.service.OpenRouterDecisionsJudgmentProvider;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Picks the backend behind smart routing and failure triage: TypeSafe Jev (default), the
 * OpenAI Decisions API, or OpenRouter's Decisions API (Microsoft-Decision-1 by default). The routing and triage feature flags are shared by both backends.
 */
public final class AgentJudgmentConfig {
    public static final String PROVIDER_KEY = "jmeter.ai.judgment.provider";
    public static final String TYPESAFE = "typesafe";
    public static final String OPENAI = "openai";
    public static final String OPENAI_API_KEY = "openai.api.key";
    public static final String OPENAI_MODEL_KEY = "openai.decisions.model";
    public static final String OPENROUTER = "openrouter";
    public static final String OPENROUTER_API_KEY = "openrouter.api.key";
    public static final String OPENROUTER_BASE_URL_KEY = "openrouter.base.url";
    public static final String OPENROUTER_MODEL_KEY = "openrouter.decisions.model";

    private AgentJudgmentConfig() {
    }

    /** The configured backend; anything other than {@code openai} or {@code openrouter} means TypeSafe Jev. */
    public static String provider() {
        String value = AiConfig.getProperty(PROVIDER_KEY, TYPESAFE);
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (OPENAI.equals(normalized) || OPENROUTER.equals(normalized)) {
            return normalized;
        }
        return TYPESAFE;
    }

    /** Name shown on routing and triage cards. */
    public static String brand() {
        try {
            switch (provider()) {
                case OPENAI:
                    return "OpenAI Decisions";
                case OPENROUTER:
                    return openRouterBrand(AiConfig.getProperty(OPENROUTER_MODEL_KEY,
                            OpenRouterDecisionsJudgmentProvider.DEFAULT_MODEL));
                default:
                    return "Jev";
            }
        } catch (RuntimeException e) {
            return "Jev";
        }
    }

    /** Names the model's vendor on OpenRouter cards, e.g. {@code microsoft/microsoft-decision-1}. */
    static String openRouterBrand(String model) {
        String value = model == null || model.isBlank()
                ? OpenRouterDecisionsJudgmentProvider.DEFAULT_MODEL
                : model.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("microsoft/")) {
            return "Microsoft Decision";
        }
        if (value.startsWith("typesafe/")) {
            return "Jev";
        }
        return "OpenRouter Decisions";
    }

    /**
     * Backend that would actually serve routing or triage, for telemetry: {@code none} when
     * both features are off or the backend's key is unusable, otherwise {@code typesafe},
     * {@code openai} or {@code openrouter:<model vendor>} (e.g. {@code openrouter:microsoft}).
     */
    public static String activeBackend() {
        try {
            if (!AgentRoutingConfig.enabled() && !AgentTriageConfig.enabled()) {
                return "none";
            }
            String backend = provider();
            String keyName = OPENAI.equals(backend) ? OPENAI_API_KEY
                    : OPENROUTER.equals(backend) ? OPENROUTER_API_KEY : AgentRoutingConfig.API_KEY;
            if (!AgentRoutingConfig.usableSecret(AiConfig.getProperty(keyName, ""))) {
                return "none";
            }
            if (OPENROUTER.equals(backend)) {
                String model = AiConfig.getProperty(OPENROUTER_MODEL_KEY, OpenRouterDecisionsJudgmentProvider.DEFAULT_MODEL);
                String m = model == null || model.isBlank() ? OpenRouterDecisionsJudgmentProvider.DEFAULT_MODEL : model;
                String vendor = m.contains("/") ? m.substring(0, m.indexOf('/')) : "custom";
                vendor = vendor.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
                return OPENROUTER + ":" + (vendor.isEmpty() ? "custom" : vendor.length() > 20 ? vendor.substring(0, 20) : vendor);
            }
            return backend;
        } catch (RuntimeException e) {
            return "none";
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
        if (OPENROUTER.equals(provider())) {
            String apiKey = AiConfig.getProperty(OPENROUTER_API_KEY, "");
            if (!AgentRoutingConfig.usableSecret(apiKey)) {
                return null;
            }
            return new OpenRouterDecisionsJudgmentProvider(apiKey.trim(),
                    AiConfig.getProperty(OPENROUTER_BASE_URL_KEY, OpenRouterDecisionsJudgmentProvider.DEFAULT_BASE_URL),
                    AiConfig.getProperty(OPENROUTER_MODEL_KEY, OpenRouterDecisionsJudgmentProvider.DEFAULT_MODEL),
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
