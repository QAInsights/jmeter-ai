package org.qainsights.jmeter.ai.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.qainsights.jmeter.ai.agent.loop.TokenUsageTracker.Counts;

/** Normalizes the usage objects returned by the agent's SDKs. */
public final class ProviderTokenUsage {
    private ProviderTokenUsage() { }

    public static Counts anthropic(JsonNode usage) {
        return new Counts(number(usage, "input_tokens"), number(usage, "cache_read_input_tokens"),
                number(usage, "cache_creation_input_tokens"), number(usage, "output_tokens"),
                number(usage.path("output_tokens_details"), "thinking_tokens"));
    }

    public static Counts openAi(JsonNode usage) {
        JsonNode details = usage.path("prompt_tokens_details");
        Long read = number(details, "cached_tokens");
        if (read == null) {
            read = number(usage, "prompt_cache_hit_tokens");
        }
        Long write = number(details, "cache_write_tokens");
        Long fresh = number(usage, "prompt_cache_miss_tokens");
        if (fresh == null) {
            fresh = subtract(number(usage, "prompt_tokens"), read, write);
        }
        return new Counts(fresh, read, write, number(usage, "completion_tokens"),
                number(usage.path("completion_tokens_details"), "reasoning_tokens"));
    }

    public static Counts gemini(JsonNode usage) {
        Long read = number(usage, "cachedContentTokenCount");
        Long fresh = subtract(number(usage, "promptTokenCount"), read, null);
        if (fresh != null) {
            fresh += orZero(number(usage, "toolUsePromptTokenCount"));
        }
        Long reasoning = number(usage, "thoughtsTokenCount");
        Long output = number(usage, "candidatesTokenCount");
        if (output != null) {
            output += orZero(reasoning);
        }
        return new Counts(fresh, read, null, output, reasoning);
    }

    public static Counts openCode(JsonNode tokens) {
        JsonNode cache = tokens.path("cache");
        Long reasoning = number(tokens, "reasoning");
        Long output = number(tokens, "output");
        if (output != null) {
            output += orZero(reasoning);
        }
        return new Counts(number(tokens, "input"), number(cache, "read"), number(cache, "write"), output, reasoning);
    }

    private static Long number(JsonNode object, String key) {
        JsonNode value = object.path(key);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                ? value.longValue() : null;
    }

    private static Long subtract(Long total, Long read, Long write) {
        return total == null ? null : Math.max(0, total - orZero(read) - orZero(write));
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
