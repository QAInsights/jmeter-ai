package org.qainsights.jmeter.ai.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Choice judgments through OpenRouter's Decisions API ({@code POST /api/alpha/decisions}), which
 * serves decision models such as Microsoft-Decision-1. The request and answer shapes match
 * TypeSafe System One, so the wire handling is shared with {@link TypeSafeJudgmentProvider}.
 */
public final class OpenRouterDecisionsJudgmentProvider implements JudgmentProvider {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api";
    public static final String DEFAULT_MODEL = "microsoft/microsoft-decision-1";

    private static final String DECISIONS_PATH = "/alpha/decisions";

    private final TypeSafeJudgmentProvider delegate;

    public OpenRouterDecisionsJudgmentProvider(String apiKey, String baseUrl, String model, Duration timeout) {
        this(TypeSafeJudgmentProvider.SHARED_CLIENT, apiKey, endpoint(baseUrl), model, timeout);
    }

    OpenRouterDecisionsJudgmentProvider(HttpClient client, String apiKey, URI endpoint, String model,
                                        Duration timeout) {
        String resolvedModel = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.delegate = new TypeSafeJudgmentProvider(client, apiKey, endpoint, resolvedModel, timeout,
                "OpenRouter");
    }

    @Override
    public Set<Capability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
        return delegate.choose(state, instructions, criteria);
    }

    @Override
    public ScoreAnswer score(Object state, String instructions, Map<String, String> levels) {
        return delegate.score(state, instructions, levels);
    }

    /** Accepts the API root ({@code https://openrouter.ai/api}), the host, a {@code /v1} URL or the full endpoint. */
    static URI endpoint(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/api" + DECISIONS_PATH)) {
            return URI.create(value);
        }
        if (value.endsWith("/v1")) {
            value = value.substring(0, value.length() - "/v1".length());
        }
        if (!value.endsWith("/api")) {
            value += "/api";
        }
        return URI.create(value + DECISIONS_PATH);
    }
}
