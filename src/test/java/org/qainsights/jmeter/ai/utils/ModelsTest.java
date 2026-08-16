package org.qainsights.jmeter.ai.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.openai.client.OpenAIClient;
import com.openai.models.models.ModelListPage;
import com.openai.services.blocking.ModelService;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the OpenAI model filtering logic in {@link Models}.
 * Verifies that the shared {@code isNonChatModel} predicate correctly excludes
 * non-chat model types (audio, TTS, embeddings, moderation, etc.) while
 * allowing chat-compatible models to pass through.
 */
class ModelsTest {

    // ==================== isNonChatModel — excluded models ====================

    @ParameterizedTest(name = "modelId=\"{0}\" → excluded")
    @ValueSource(strings = {
            "text-embedding-3-small",
            "text-embedding-3-large",
            "text-embedding-ada-002",
            "text-moderation-latest",
            "omni-moderation-latest",
            "text-moderation-004",
            "dall-e-3",
            "dall-e-2",
            "tts-1",
            "tts-1-hd",
            "whisper-1",
            "whisper-large-v3",
            "gpt-3.5-turbo-instruct",
            "gpt-4-realtime-preview",
            "gpt-4o-audio-preview",
            "gpt-4o-search-preview",
            "computer-use-preview"
    })
    void isNonChatModel_returnsTrueForExcludedTypes(String modelId) {
        assertTrue(Models.isNonChatModel(modelId),
                () -> "Expected \"%s\" to be excluded as a non-chat model".formatted(modelId));
    }

    @ParameterizedTest(name = "modelId=\"{0}\" → excluded (case-insensitive)")
    @ValueSource(strings = {
            "TEXT-EMBEDDING-3-SMALL",
            "Omni-Moderation-Latest",
            "DALL-E-3",
            "TTS-1",
            "WHISPER-1",
            "GPT-4-REALTIME-PREVIEW",
            "COMPUTER-USE-PREVIEW"
    })
    void isNonChatModel_isCaseInsensitive(String modelId) {
        assertTrue(Models.isNonChatModel(modelId),
                () -> "Expected \"%s\" to be excluded regardless of case".formatted(modelId));
    }

    @ParameterizedTest(name = "null/empty → excluded")
    @NullAndEmptySource
    void isNonChatModel_returnsTrueForNullOrBlank(String modelId) {
        assertTrue(Models.isNonChatModel(modelId),
                () -> "Expected %s to be excluded".formatted(modelId));
    }

    // ==================== isNonChatModel — allowed models ====================

    @ParameterizedTest(name = "modelId=\"{0}\" → allowed")
    @ValueSource(strings = {
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-4",
            "gpt-3.5-turbo",
            "o1",
            "o1-mini",
            "o1-preview",
            "o3",
            "o3-mini",
            "o4-mini",
            "chatgpt-4o-latest"
    })
    void isNonChatModel_returnsFalseForChatModels(String modelId) {
        assertFalse(Models.isNonChatModel(modelId),
                () -> "Expected \"%s\" to be allowed as a chat model".formatted(modelId));
    }

    // ==================== Boundary / edge-case assertions ====================

    @Test
    void isNonChatModel_partialSubstringMatch_excludesModel() {
        // "search" is a substring of "gpt-4o-search-preview" — should be excluded
        assertTrue(Models.isNonChatModel("gpt-4o-search-preview"),
                "Model IDs containing 'search' should be excluded");
    }

    @Test
    void isNonChatModel_noFalsePositiveOnUnrelatedModel() {
        // Ensure unrelated model names are not accidentally excluded
        assertFalse(Models.isNonChatModel("deepseek-v4-flash-free"),
                "Non-OpenAI chat model IDs must not be excluded");
    }

    @Test
    void isNonChatModel_customOpenAiCompatibleModelIsAllowed() {
        // A custom model served via openai.base.url that is chat-compatible
        assertFalse(Models.isNonChatModel("my-local-chat-model"),
                "Custom OpenAI-compatible chat model IDs should be allowed");
    }

    // ==================== Client reuse — getOpenAiModels ====================

    @Test
    void getOpenAiModels_usesPassedInClient_notRebuilt() {
        OpenAIClient mockClient = Mockito.mock(OpenAIClient.class);
        ModelService mockModelService = Mockito.mock(ModelService.class);
        ModelListPage mockPage = Mockito.mock(ModelListPage.class);

        when(mockClient.models()).thenReturn(mockModelService);
        when(mockModelService.list()).thenReturn(mockPage);

        Models.getOpenAiModels(mockClient);

        verify(mockClient).models();
        verify(mockModelService).list();
    }

    @Test
    void getOpenAiModels_nullClient_returnsNullGracefully() {
        // getOpenAiModels catches exceptions internally and returns null
        com.openai.models.models.ModelListPage result = Models.getOpenAiModels(null);
        assertTrue(result == null,
                "Passing null client should return null (exception caught internally)");
    }
}
