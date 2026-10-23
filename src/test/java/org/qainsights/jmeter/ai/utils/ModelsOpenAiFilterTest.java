package org.qainsights.jmeter.ai.utils;

import com.anthropic.client.AnthropicClient;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelsOpenAiFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "gpt-4o", "gpt-4.1-mini", "gpt-5", "gpt-5.5", "gpt-6-sol",
            "o1", "o1-2024-12-17", "o3", "o3-mini", "o4-mini", "o4-mini-2025-04-16", "chat-latest"})
    void officialEndpointListsChatModels(String id) {
        assertTrue(Models.isOpenAiModelIncluded(id, false), id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gpt-image-1", "gpt-image-2.5-flare", "GPT-Image-1", "dall-e-3", "sora-2", "sora-2-pro",
            "babbage-002", "text-embedding-3-small", "omni-moderation-latest", "whisper-1",
            "gpt-4o-mini-tts", "gpt-4o-transcribe", "gpt-realtime", "gpt-audio", "gpt-4o-search-preview",
            "gpt-3.5-turbo-instruct", "o1-pro", "o1-pro-2025-03-19", "gpt-5-pro", "gpt-5.2-pro-2025-12-11",
            "gpt-5-codex", "gpt-5.3-codex", "codex-mini-latest", "o4-mini-deep-research",
            "computer-use-preview", "gpt-live-1", "", "  "})
    void officialEndpointHidesNonChatAndResponsesOnlyModels(String id) {
        assertFalse(Models.isOpenAiModelIncluded(id, false), id);
    }

    @Test
    void nullModelIdIsHidden() {
        assertFalse(Models.isOpenAiModelIncluded(null, false));
        assertFalse(Models.isOpenAiModelIncluded(null, true));
    }

    @Test
    void gatewayKeepsCustomNamesButHidesNonChatModels() {
        assertTrue(Models.isOpenAiModelIncluded("corp-gpt-4o", true));
        assertTrue(Models.isOpenAiModelIncluded("deepseek-v4-flash-free", true));
        assertTrue(Models.isOpenAiModelIncluded("anthropic/claude-sonnet-4", true));
        assertTrue(Models.isOpenAiModelIncluded("openai/gpt-5-codex", true));
        assertFalse(Models.isOpenAiModelIncluded("text-embedding-3-small", true));
        assertFalse(Models.isOpenAiModelIncluded("google/gemini-2.5-flash-image", true));
        assertFalse(Models.isOpenAiModelIncluded("omni-moderation-latest", true));
    }

    @Test
    void openAiListingUsesTheSuppliedClient() {
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.models()).thenThrow(new IllegalStateException("listing failed"));

        assertNull(Models.getOpenAiModels(client));
        verify(client).models();
    }

    @Test
    void anthropicListingUsesTheSuppliedClient() {
        AnthropicClient client = mock(AnthropicClient.class);
        when(client.models()).thenThrow(new IllegalStateException("listing failed"));

        assertNull(Models.getAnthropicModels(client));
        verify(client).models();
    }
}
