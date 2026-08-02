package org.qainsights.jmeter.ai.service.reasoning;

import java.util.Map;

import com.openai.core.JsonValue;

/**
 * Reads the non-standard {@code reasoning_content} field out of OpenAI SDK
 * response objects for OpenAI-compatible providers that return reasoning
 * (DeepSeek's deepseek-reasoner, xAI's reasoning Grok models). The openai-java
 * SDK has no typed accessor for it, so it arrives in the additional-properties
 * map of the streaming delta and of the completion message. There is no toggle
 * to send for these models - only reasoning text to display.
 */
public final class DeepSeekReasoning {

    public static final String REASONING_CONTENT_KEY = "reasoning_content";

    private DeepSeekReasoning() {
    }

    /**
     * @param additionalProperties the {@code _additionalProperties()} map of a
     *                             streaming delta or completion message
     * @return the reasoning text, or null when absent or unreadable
     */
    public static String reasoningContent(Map<String, JsonValue> additionalProperties) {
        if (additionalProperties == null) {
            return null;
        }
        JsonValue value = additionalProperties.get(REASONING_CONTENT_KEY);
        if (value == null) {
            return null;
        }
        try {
            return value.convert(String.class);
        } catch (Exception e) {
            return null;
        }
    }
}
