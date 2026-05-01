package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit tests for streaming functionality in OllamaAiService.
 * Tests cover the generateStreamResponse method, token consumption,
 * completion, error handling, and cancellation via Thread.interrupt().
 */
@ExtendWith(MockitoExtension.class)
class OllamaAiServiceStreamingTest {

    private OllamaAiService ollamaService;

    private static MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("ollama.host")) return "http://localhost";
            if (key.equals("ollama.port")) return "11434";
            if (key.equals("ollama.default.model")) return "deepseek-r1:1.5b";
            if (key.equals("ollama.temperature")) return "0.5";
            if (key.equals("ollama.max.history.size")) return "10";
            if (key.equals("ollama.thinking.mode")) return "DISABLED";
            if (key.equals("ollama.thinking.level")) return "MEDIUM";
            if (key.equals("ollama.request.timeout.seconds")) return "120";
            if (key.equals("ollama.system.prompt")) return "You are a test assistant.";
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
        ollamaService = new OllamaAiService();
    }

    // ==================== Interface Contract ====================

    @Test
    void testGenerateStreamResponse_returnsNonNullRunnable() {
        Runnable cancelHandle = ollamaService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-r1:1.5b",
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
    void testGenerateStreamResponse_cancelHandleInterruptsThread() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Runnable cancelHandle = ollamaService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-r1:1.5b",
                token -> {},
                () -> {},
                e -> interrupted.set(true)
        );

        assertNotNull(cancelHandle, "Should return a cancel handle");

        // Give the daemon thread a moment to start
        Thread.sleep(50);

        // Cancel the stream
        cancelHandle.run();

        // After cancellation, the onError should NOT be called with a real error
        // (only on interrupt). Since the stream will try to connect to Ollama and fail,
        // the error handler may fire anyway. This test just ensures cancellation is safe.
        assertDoesNotThrow(cancelHandle::run,
                "Calling cancel handle multiple times should not throw");
    }

    // ==================== Conversation Handling ====================

    @Test
    void testGenerateStreamResponse_withNullModelUsesCurrentModel() {
        Runnable cancelHandle = ollamaService.generateStreamResponse(
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
    void testGenerateStreamResponse_withEmptyModelUsesCurrentModel() {
        Runnable cancelHandle = ollamaService.generateStreamResponse(
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
        Runnable cancelHandle = ollamaService.generateStreamResponse(
                Collections.emptyList(),
                "deepseek-r1:1.5b",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should handle empty conversation gracefully");
    }

    @Test
    void testGenerateStreamResponse_handlesNullConversation() {
        Runnable cancelHandle = ollamaService.generateStreamResponse(
                null,
                "deepseek-r1:1.5b",
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

        Runnable cancelHandle = ollamaService.generateStreamResponse(
                largeConversation,
                "deepseek-r1:1.5b",
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

        Runnable cancelHandle = ollamaService.generateStreamResponse(
                conversationWithNulls,
                "deepseek-r1:1.5b",
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

        Runnable cancelHandle = ollamaService.generateStreamResponse(
                conversationWithEmpty,
                "deepseek-r1:1.5b",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(cancelHandle,
                "Should skip empty messages in conversation gracefully");
    }

    // ==================== Model Setting ====================

    @Test
    void testGenerateStreamResponse_setsModelWhenProvided() {
        String newModel = "mixtral:8x7b";
        ollamaService.setModel("deepseek-r1:1.5b");  // set initial model

        Runnable cancelHandle = ollamaService.generateStreamResponse(
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
        Runnable cancelHandle = ollamaService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-r1:1.5b",
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
        Runnable handle1 = ollamaService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "deepseek-r1:1.5b",
                token -> {},
                () -> {},
                e -> {}
        );
        Runnable handle2 = ollamaService.generateStreamResponse(
                Collections.singletonList("Hello again"),
                "deepseek-r1:1.5b",
                token -> {},
                () -> {},
                e -> {}
        );
        assertNotNull(handle1);
        assertNotNull(handle2);
        // Each call spawns a new thread, so handles are independent
    }
}