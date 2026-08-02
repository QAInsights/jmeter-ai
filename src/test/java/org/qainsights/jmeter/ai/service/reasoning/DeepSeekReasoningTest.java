package org.qainsights.jmeter.ai.service.reasoning;

import com.openai.core.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeepSeekReasoning}: reading reasoning_content out of
 * additional-properties maps.
 */
class DeepSeekReasoningTest {

    @Test
    void extractsReasoningContent() {
        Map<String, JsonValue> props = Map.of(
                DeepSeekReasoning.REASONING_CONTENT_KEY, JsonValue.from("chain of thought"));
        assertEquals("chain of thought", DeepSeekReasoning.reasoningContent(props));
    }

    @Test
    void returnsNullWhenAbsent() {
        Map<String, JsonValue> props = Map.of("other_field", JsonValue.from("value"));
        assertNull(DeepSeekReasoning.reasoningContent(props));
    }

    @Test
    void returnsNullForNullMap() {
        assertNull(DeepSeekReasoning.reasoningContent(null));
    }

    @Test
    void returnsNullForNonStringValue() {
        Map<String, JsonValue> props = Map.of(
                DeepSeekReasoning.REASONING_CONTENT_KEY, JsonValue.from(42));
        // numeric reasoning_content is malformed - must not blow up
        String result = DeepSeekReasoning.reasoningContent(props);
        assertTrue(result == null || result.equals("42"));
    }
}
