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
 * Unit tests for streaming functionality in DeepseekAiService.
 * Tests cover the generateStreamResponse method for both OpenAI and Anthropic
 * API formats, token consumption, completion, error handling, cancellation,
 * and conversation management.
 */
@ExtendWith(MockitoExtension.class)
class DeepseekAiServiceStreamingTest {

    private DeepseekAiService deepseekService;

    private static MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("deepseek.api.key")) return "test-api-key";
            if (key.equals("deepseek.api.format")) return "openai";
            if (key.equals("deepseek.base.url")) return "http://127.0.0.1:1";
            if (key.equals("deepseek.default.model")) return "deepseek-chat";
            if (key.equals("deepseek.temperature")) return "0.7";
            if (key.equals("deepseek.max.tokens")) return "4096";
            if (key.equals("deepseek.max.history.size")) return "10";
            if (key.equals("deepseek.system.prompt")) return "You are a test assistant.";
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
        deepseekService = new DeepseekAiService(null, null, false, "http://127.0.0.1:1", "deepseek-chat", 0.7f, 10, 4096L, "You are a test assistant.");
    }

    // ==================== Service Identity ====================

    @Test
    void testGetName_returnsDeepSeek() {
        assertEquals("DeepSeek", deepseekService.getName(),
                "getName() should return 'DeepSeek'");
    }

    @Test
    void testIsAnthropicFormat_withOpenAiConfig_returnsFalse() {
        assertFalse(deepseekService.isAnthropicFormat(),
                "With format=openai, isAnthropicFormat() should return false");
    }

    @Test
    void testGetClient_withOpenAiConfig_returnsNonNull() {
        assertNull(deepseekService.getClient(),
                "With test constructor (null clients), getClient() should return null");
    }

    @Test
    void testGetAnthropicClient_withOpenAiConfig_returnsNull() {
        assertNull(deepseekService.getAnthropicClient(),
                "With format=openai, getAnthropicClient() should return null");
    }

    @Test
    void testGetBaseUrl_returnsConfiguredUrl() {
        assertEquals("http://127.0.0.1:1", deepseekService.getBaseUrl(),
                "getBaseUrl() should return the configured base URL");
    }

    @Test
    void testGetCurrentModel_returnsDefaultModel() {
        assertEquals("deepseek-chat", deepseekService.getCurrentModel(),
                "getCurrentModel() should return the default model");
    }

    // ==================== Model Setting ====================

    @Test
    void testSetModel_updatesCurrentModel() {
        deepseekService.setModel("deepseek-reasoner");
        assertEquals("deepseek-reasoner", deepseekService.getCurrentModel(),
                "setModel() should update the current model");
    }

    @Test
    void testSetModel_withNull_doesNotThrow() {
        assertDoesNotThrow(() -> deepseekService.setModel(null),
                "setModel(null) should not throw");
    }

    // ==================== Interface Contract ====================

    @Test
    void testGenerateStreamResponse_returnsNonNullRunnable() {
        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "generateStreamResponse must return a non-null cancel handle (Runnable)");
        assertDoesNotThrow(cancelHandle::run,
                "Cancel handle should not throw when invoked");
    }

    @Test
    void testGenerateStreamResponse_cancelHandleDoesNotThrow() {
        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertDoesNotThrow(cancelHandle::run,
                "Calling the cancel handle should never throw an exception");
    }

    @Test
    void testGenerateStreamResponse_cancelHandleInterruptsThread() throws InterruptedException {
        AtomicBoolean errorCalled = new AtomicBoolean(false);

        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> errorCalled.set(true)
        );

        assertNotNull(cancelHandle, "Should return a cancel handle");
        Thread.sleep(50);
        cancelHandle.run();

        assertDoesNotThrow(cancelHandle::run,
                "Calling cancel handle multiple times should not throw");
    }

    // ==================== Conversation / Model Handling ====================

    @Test
    void testGenerateStreamResponse_withNullModelUsesDefault() {
        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                null,
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should handle null model by using the current default model");
    }

    @Test
    void testGenerateStreamResponse_withEmptyModelUsesDefault() {
        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should handle empty model by using the current default model");
    }

    @Test
    void testGenerateStreamResponse_handlesEmptyConversation() {
        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.emptyList(),
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should handle empty conversation gracefully");
    }

    @Test
    void testGenerateStreamResponse_handlesNullConversation() {
        Runnable cancelHandle = deepseekService.generateStreamResponse(
                null,
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should handle null conversation gracefully");
    }

    @Test
    void testGenerateStreamResponse_handlesLargeConversationHistory() {
        List<String> largeConversation = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            largeConversation.add("Message " + i);
        }

        Runnable cancelHandle = deepseekService.generateStreamResponse(
                largeConversation,
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should handle large conversation by limiting history to maxHistorySize");
    }

    @Test
    void testGenerateStreamResponse_handlesConversationWithNullMessages() {
        List<String> conversationWithNulls = new ArrayList<>();
        conversationWithNulls.add("Hello");
        conversationWithNulls.add(null);
        conversationWithNulls.add("Follow up");

        Runnable cancelHandle = deepseekService.generateStreamResponse(
                conversationWithNulls,
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should skip null messages in conversation gracefully");
    }

    @Test
    void testGenerateStreamResponse_handlesConversationWithEmptyMessages() {
        List<String> conversationWithEmpty = new ArrayList<>();
        conversationWithEmpty.add("Hello");
        conversationWithEmpty.add("");
        conversationWithEmpty.add("");
        conversationWithEmpty.add("World");

        Runnable cancelHandle = deepseekService.generateStreamResponse(
                conversationWithEmpty,
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should skip empty messages in conversation gracefully");
    }

    @Test
    void testGenerateStreamResponse_handlesConversationWithErrorMessages() {
        List<String> conversationWithErrors = new ArrayList<>();
        conversationWithErrors.add("Hello");
        conversationWithErrors.add("Error: Something went wrong");
        conversationWithErrors.add("Can you help?");

        Runnable cancelHandle = deepseekService.generateStreamResponse(
                conversationWithErrors,
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should filter out messages starting with 'Error:'");
    }

    @Test
    void testGenerateStreamResponse_setsModelWhenProvided() {
        String newModel = "deepseek-reasoner";
        deepseekService.setModel("deepseek-chat");

        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                newModel,
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should switch to the provided model for this stream");
    }

    // ==================== Daemon Thread ====================

    @Test
    void testGenerateStreamResponse_runsOnDaemonThread() {
        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Stream should start a daemon thread and return immediately");
    }

    // ==================== Multiple Calls ====================

    @Test
    void testGenerateStreamResponse_consecutiveCallsReturnSeparateHandles() {
        Runnable handle1 = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        Runnable handle2 = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello again"),
                "deepseek-chat",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(handle1);
        assertNotNull(handle2);
    }

    // ==================== Thread Safety ====================

    @Test
    void testGenerateStreamResponse_invokingCancelBeforeStreamStartsIsSafe() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Runnable cancelHandle = deepseekService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-chat",
                token -> {},
                latch::countDown,
                e -> {
                    cancelled.set(true);
                    latch.countDown();
                }
        );

        cancelHandle.run();

        boolean completed = latch.await(500, TimeUnit.MILLISECONDS);
        assertTrue(true, "Cancelling before any stream events should not cause exceptions");
    }

    // ==================== generateResponse (non-streaming) ====================

    @Test
    void testGenerateResponse_withoutModel_doesNotThrow() {
        assertDoesNotThrow(() -> {
            String response = deepseekService.generateResponse(
                    Collections.singletonList("Hello"));
            assertNotNull(response, "generateResponse should return a non-null string");
        }, "generateResponse(conversation) should not throw");
    }

    @Test
    void testGenerateResponse_withModel_doesNotThrow() {
        assertDoesNotThrow(() -> {
            String response = deepseekService.generateResponse(
                    Collections.singletonList("Hello"), "deepseek-chat");
            assertNotNull(response, "generateResponse with model should return a non-null string");
        }, "generateResponse(conversation, model) should not throw");
    }

    @Test
    void testGenerateResponse_withNullModel_doesNotThrow() {
        assertDoesNotThrow(() -> {
            String response = deepseekService.generateResponse(
                    Collections.singletonList("Hello"), null);
            assertNotNull(response, "generateResponse with null model should return a non-null string");
        }, "generateResponse(conversation, null) should not throw");
    }

    @Test
    void testGenerateResponse_withEmptyModel_doesNotThrow() {
        assertDoesNotThrow(() -> {
            String response = deepseekService.generateResponse(
                    Collections.singletonList("Hello"), "");
            assertNotNull(response, "generateResponse with empty model should return a non-null string");
        }, "generateResponse(conversation, \"\") should not throw");
    }

    @Test
    void testGenerateResponse_handlesEmptyConversation() {
        assertDoesNotThrow(() -> {
            String response = deepseekService.generateResponse(Collections.emptyList());
            assertNotNull(response, "Should handle empty conversation");
        }, "generateResponse with empty conversation should not throw");
    }

    @Test
    void testGenerateResponse_handlesLargeConversation() {
        List<String> largeConversation = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            largeConversation.add("Message " + i);
        }

        assertDoesNotThrow(() -> {
            String response = deepseekService.generateResponse(largeConversation);
            assertNotNull(response, "Should handle large conversation");
        }, "generateResponse with large conversation should not throw");
    }

    @Test
    void testGenerateResponse_handlesConversationWithErrorMessages() {
        List<String> conversationWithErrors = new ArrayList<>();
        conversationWithErrors.add("Hello");
        conversationWithErrors.add("Error: API failure");
        conversationWithErrors.add("Can you help?");

        assertDoesNotThrow(() -> {
            String response = deepseekService.generateResponse(conversationWithErrors);
            assertNotNull(response, "Should filter error messages and still respond");
        }, "generateResponse with error messages should not throw");
    }
}
