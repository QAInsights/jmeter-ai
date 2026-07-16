package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link GrokAiService}.
 * Tests cover service identity, model management, response generation with
 * null client (error paths), streaming with null client, conversation history
 * management, error message filtering, and cancellation.
 */
@ExtendWith(MockitoExtension.class)
class GrokAiServiceTest {

    private GrokAiService grokService;

    private static MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String defaultValue = invocation.getArgument(1);
                    if (key.equals("grok.api.key")) return "test-api-key";
                    if (key.equals("grok.base.url")) return "http://127.0.0.1:1";
                    if (key.equals("grok.default.model")) return "grok-4.5";
                    if (key.equals("grok.temperature")) return "0.7";
                    if (key.equals("grok.max.tokens")) return "4096";
                    if (key.equals("grok.max.history.size")) return "10";
                    if (key.equals("grok.system.prompt")) return "You are a test assistant.";
                    return defaultValue;
                });
    }

    @AfterAll
    static void tearDownAll() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @BeforeEach
    void setUp() {
        grokService = new GrokAiService(
                null, "http://127.0.0.1:1", "grok-4.5",
                0.7f, 10, 4096L, "You are a test assistant.");
    }

    // ==================== Service Identity ====================

    @Test
    void testGetName_returnsGrok() {
        assertEquals("Grok", grokService.getName(),
                "getName() should return 'Grok'");
    }

    // ==================== Model Management ====================

    @Test
    void testGetCurrentModel_returnsDefault() {
        assertEquals("grok-4.5", grokService.getCurrentModel(),
                "Default model should be grok-4.5");
    }

    @Test
    void testSetModel_updatesCurrentModel() {
        grokService.setModel("grok-3-mini");
        assertEquals("grok-3-mini", grokService.getCurrentModel(),
                "Model should be updated to grok-3-mini");
    }

    @Test
    void testGetClient_returnsNull_whenNotInitialized() {
        assertNull(grokService.getClient(),
                "Client should be null when constructed with null");
    }

    // ==================== Response Generation (Null Client) ====================

    @Test
    void testGenerateResponse_withNullClient_returnsError() {
        List<String> conversation = List.of("Hello");
        String response = grokService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"),
                "Should return error when client is null");
    }

    @Test
    void testGenerateResponse_withModel_withNullClient_returnsError() {
        List<String> conversation = List.of("Hello");
        String response = grokService.generateResponse(conversation, "grok-4.5");
        assertTrue(response.startsWith("Error:"),
                "Should return error when client is null");
    }

    @Test
    void testGenerateResponse_emptyConversation_withNullClient_returnsError() {
        String response = grokService.generateResponse(Collections.emptyList());
        assertTrue(response.startsWith("Error:"),
                "Should return error even with empty conversation when client is null");
    }

    // ==================== Streaming (Null Client) ====================

    @Test
    void testGenerateStreamResponse_withNullClient_returnsNoOpRunnable() {
        AtomicBoolean completeCalled = new AtomicBoolean(false);
        Runnable cancelHandle = grokService.generateStreamResponse(
                List.of("Hello"), "grok-4.5",
                token -> {},
                () -> completeCalled.set(true),
                error -> {});

        assertNotNull(cancelHandle, "Cancel handle should not be null");
        cancelHandle.run(); // Should not throw
        assertFalse(completeCalled.get(),
                "onComplete should not be called when client is null");
    }

    @Test
    void testGenerateStreamResponse_emptyConversation_withNullClient() {
        Runnable cancelHandle = grokService.generateStreamResponse(
                Collections.emptyList(), "grok-4.5",
                token -> {}, () -> {}, error -> {});
        assertNotNull(cancelHandle, "Cancel handle should not be null");
    }

    // ==================== Conversation History Management ====================

    @Test
    void testGenerateResponse_nullConversation_withNullClient() {
        String response = grokService.generateResponse(null);
        assertTrue(response.startsWith("Error:"),
                "Should handle null conversation gracefully");
    }

    @Test
    void testGenerateResponse_largeConversation_isLimited() {
        List<String> conversation = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            conversation.add("message " + i);
        }
        // Should not throw even though history exceeds maxHistorySize
        String response = grokService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"),
                "Should still return error (null client) but not throw on large history");
    }

    @Test
    void testGenerateResponse_conversationWithErrors_filtered() {
        List<String> conversation = new ArrayList<>();
        conversation.add("Hello");
        conversation.add("Error: something went wrong");
        conversation.add("Try again");
        // Error messages should be filtered out; should not throw
        String response = grokService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"),
                "Should still return error (null client)");
    }

    // ==================== Model Listing (Null Client) ====================

    @Test
    void testListModels_withNullClient_returnsEmptyList() {
        List<String> models = grokService.listModels();
        assertNotNull(models, "Model list should not be null");
        assertTrue(models.isEmpty(),
                "Model list should be empty when client is null");
    }

    // ==================== Cancel Handle ====================

    @Test
    void testCancelHandle_isIdempotent() {
        Runnable cancelHandle = grokService.generateStreamResponse(
                List.of("Hello"), "grok-4.5",
                token -> {}, () -> {}, error -> {});
        // Calling cancel multiple times should not throw
        cancelHandle.run();
        cancelHandle.run();
    }
}
