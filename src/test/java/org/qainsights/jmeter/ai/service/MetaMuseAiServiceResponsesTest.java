package org.qainsights.jmeter.ai.service;

import com.openai.core.JsonValue;
import com.openai.models.responses.Response;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Responses API extraction helpers in
 * {@link MetaMuseAiService}: output text and reasoning summary extraction.
 */
class MetaMuseAiServiceResponsesTest {

    /** Builds a real Response from its JSON shape, bypassing strict builders. */
    private static Response response(List<Object> outputItems) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "resp_1");
        json.put("object", "response");
        json.put("model", "muse-spark-1.1");
        json.put("status", "completed");
        json.put("output", outputItems);
        return JsonValue.from(json).convert(Response.class);
    }

    private static Map<String, Object> reasoningItem(String summaryText) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "summary_text");
        part.put("text", summaryText);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "reasoning");
        item.put("summary", List.of(part));
        return item;
    }

    private static Map<String, Object> messageItem(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "output_text");
        content.put("text", text);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", "assistant");
        item.put("content", List.of(content));
        return item;
    }

    @Test
    void extractOutputTextConcatenatesMessageText() {
        Response response = response(List.of(
                reasoningItem("thoughts"),
                messageItem("part one "),
                messageItem("part two")));
        assertEquals("part one part two", MetaMuseAiService.extractOutputText(response));
    }

    @Test
    void extractOutputTextReturnsPlaceholderWhenEmpty() {
        Response response = response(List.of(reasoningItem("only thoughts")));
        assertEquals("No content available", MetaMuseAiService.extractOutputText(response));
    }

    @Test
    void extractReasoningSummaryConcatenatesSummaryParts() {
        Response response = response(List.of(
                reasoningItem("first "),
                reasoningItem("second"),
                messageItem("answer")));
        assertEquals("first second", MetaMuseAiService.extractReasoningSummary(response));
    }

    @Test
    void extractReasoningSummaryReturnsNullWhenAbsent() {
        Response response = response(List.of(messageItem("plain answer")));
        assertNull(MetaMuseAiService.extractReasoningSummary(response));
    }
}
