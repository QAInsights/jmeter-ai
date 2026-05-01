package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unit tests for AiResponseRouter streaming functionality.
 * Tests cover the generateStreamResponse method routing logic
 * for Anthropic, OpenAI, and Ollama providers.
 */
@ExtendWith(MockitoExtension.class)
class AiResponseRouterStreamingTest {

    @Mock
    private ClaudeService claudeService;

    @Mock
    private OpenAiService openAiService;

    @Mock
    private OllamaAiService ollamaService;

    private AiResponseRouter router;

    private static MockedStatic<AiConfig> aiConfigMockedStatic;
    private final List<String> conversation = Collections.singletonList("Hello");

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("anthropic.api.key")) return "test-key";
            if (key.equals("openai.api.key")) return "test-key";
            if (key.equals("claude.default.model")) return "claude-sonnet-4-20250514";
            if (key.equals("claude.temperature")) return "0.5";
            if (key.equals("claude.max.tokens")) return "1024";
            if (key.equals("claude.max.history.size")) return "10";
            if (key.equals("claude.system.prompt")) return "prompt";
            if (key.equals("anthropic.log.level")) return "";
            if (key.equals("openai.default.model")) return "gpt-4o";
            if (key.equals("openai.temperature")) return "0.7";
            if (key.equals("openai.max.tokens")) return "4096";
            if (key.equals("openai.max.history.size")) return "10";
            if (key.equals("openai.system.prompt")) return "prompt";
            if (key.equals("openai.log.level")) return "";
            if (key.equals("ollama.host")) return "http://localhost";
            if (key.equals("ollama.port")) return "11434";
            if (key.equals("ollama.default.model")) return "deepseek-r1:1.5b";
            if (key.equals("ollama.temperature")) return "0.5";
            if (key.equals("ollama.max.history.size")) return "10";
            if (key.equals("ollama.thinking.mode")) return "DISABLED";
            if (key.equals("ollama.thinking.level")) return "MEDIUM";
            if (key.equals("ollama.request.timeout.seconds")) return "120";
            if (key.equals("ollama.system.prompt")) return "prompt";
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
        // Use constructor injection manually with mock services
        router = new AiResponseRouter(claudeService, openAiService, ollamaService);
    }

    // ==================== Routing to Anthropic ====================

    @Test
    void testGenerateStreamResponse_withAnthropicModelRoutesToClaudeService() {
        String anthropicModel = "claude-sonnet-4-20250514";
        Runnable expectedHandle = () -> {};

        when(claudeService.generateStreamResponse(
                eq(conversation),
                eq(anthropicModel),
                any(),
                any(),
                any()
        )).thenReturn(expectedHandle);

        Runnable result = router.generateStreamResponse(
                anthropicModel, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertSame(expectedHandle, result,
                "Anthropic model should route to claudeService.generateStreamResponse");
        verify(claudeService).generateStreamResponse(
                eq(conversation), eq(anthropicModel),
                any(), any(), any()
        );
        verifyNoInteractions(openAiService, ollamaService);
    }

    @Test
    void testGenerateStreamResponse_withAnthropicModelAndNullModelUsesDefault() {
        when(claudeService.getCurrentModel()).thenReturn("claude-sonnet-4-20250514");
        Runnable expectedHandle = () -> {};

        when(claudeService.generateStreamResponse(
                eq(conversation),
                eq("claude-sonnet-4-20250514"),
                any(),
                any(),
                any()
        )).thenReturn(expectedHandle);

        Runnable result = router.generateStreamResponse(
                null, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertSame(expectedHandle, result,
                "Null model should fall back to claudeService.getCurrentModel()");
    }

    // ==================== Routing to OpenAI ====================

    @Test
    void testGenerateStreamResponse_withOpenAiModelRoutesToOpenAiService() {
        String openAiModel = "openai:gpt-4o";
        Runnable expectedHandle = () -> {};

        when(openAiService.generateStreamResponse(
                eq(conversation),
                eq("gpt-4o"),
                any(),
                any(),
                any()
        )).thenReturn(expectedHandle);

        Runnable result = router.generateStreamResponse(
                openAiModel, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertSame(expectedHandle, result,
                "OpenAI model (prefixed with 'openai:') should route to openAiService");
        verify(openAiService).generateStreamResponse(
                eq(conversation), eq("gpt-4o"),
                any(), any(), any()
        );
        verifyNoInteractions(claudeService, ollamaService);
    }

    @Test
    void testGenerateStreamResponse_withOpenAiGpt35Model() {
        String openAiModel = "openai:gpt-3.5-turbo";
        Runnable expectedHandle = () -> {};

        when(openAiService.generateStreamResponse(
                eq(conversation),
                eq("gpt-3.5-turbo"),
                any(),
                any(),
                any()
        )).thenReturn(expectedHandle);

        Runnable result = router.generateStreamResponse(
                openAiModel, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertSame(expectedHandle, result);
        verify(openAiService).generateStreamResponse(
                eq(conversation), eq("gpt-3.5-turbo"),
                any(), any(), any()
        );
    }

    @Test
    void testGenerateStreamResponse_withOpenAiModelStripsPrefixCorrectly() {
        String openAiModel = "openai:gpt-4-turbo";
        String expectedModel = "gpt-4-turbo";
        Runnable expectedHandle = () -> {};

        when(openAiService.generateStreamResponse(
                eq(conversation),
                eq(expectedModel),
                any(),
                any(),
                any()
        )).thenReturn(expectedHandle);

        Runnable result = router.generateStreamResponse(
                openAiModel, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertSame(expectedHandle, result,
                "openai: prefix should be stripped before passing to OpenAiService");
    }

    // ==================== Routing to Ollama ====================

    @Test
    void testGenerateStreamResponse_withOllamaModelRoutesToOllamaService() {
        String ollamaModel = "ollama:llama3.1";
        Runnable expectedHandle = () -> {};

        when(ollamaService.generateStreamResponse(
                eq(conversation),
                eq("llama3.1"),
                any(),
                any(),
                any()
        )).thenReturn(expectedHandle);

        Runnable result = router.generateStreamResponse(
                ollamaModel, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertSame(expectedHandle, result,
                "Ollama model (prefixed with 'ollama:') should route to ollamaService");
        verify(ollamaService).generateStreamResponse(
                eq(conversation), eq("llama3.1"),
                any(), any(), any()
        );
        verifyNoInteractions(claudeService, openAiService);
    }

    @Test
    void testGenerateStreamResponse_withOllamaModelStripsPrefixCorrectly() {
        String ollamaModel = "ollama:mixtral:8x7b";
        String expectedModel = "mixtral:8x7b";
        Runnable expectedHandle = () -> {};

        when(ollamaService.generateStreamResponse(
                eq(conversation),
                eq(expectedModel),
                any(),
                any(),
                any()
        )).thenReturn(expectedHandle);

        Runnable result = router.generateStreamResponse(
                ollamaModel, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertSame(expectedHandle, result,
                "ollama: prefix should be stripped before passing to OllamaAiService");
    }

    // ==================== Callback Forwarding ====================

    @Test
    void testGenerateStreamResponse_forwardsTokenConsumerToService() {
        String anthropicModel = "claude-sonnet-4-20250514";
        Consumer<String> tokenConsumer = token -> {};
        Runnable onComplete = () -> {};
        Consumer<Exception> onError = e -> {};

        when(claudeService.generateStreamResponse(
                eq(conversation), eq(anthropicModel),
                eq(tokenConsumer), eq(onComplete), eq(onError)
        )).thenReturn(() -> {});

        router.generateStreamResponse(
                anthropicModel, conversation,
                tokenConsumer, onComplete, onError
        );

        verify(claudeService).generateStreamResponse(
                eq(conversation), eq(anthropicModel),
                eq(tokenConsumer), eq(onComplete), eq(onError)
        );
    }

    // ==================== Return Type Contract ====================

    @Test
    void testGenerateStreamResponse_returnsRunnableCancelHandle_fallbackToClaude() {
        when(claudeService.getCurrentModel()).thenReturn("claude-sonnet-4-20250514");
        when(claudeService.generateStreamResponse(
                any(), any(), any(), any(), any()
        )).thenReturn(() -> {});

        Runnable cancelHandle = router.generateStreamResponse(
                null, conversation,
                token -> {},
                () -> {},
                e -> {}
        );

        assertNotNull(cancelHandle,
                "generateStreamResponse must always return a non-null Runnable cancel handle");
    }

    // ==================== AiService Resolution ====================

    @Test
    void testResolveAiService_forAnthropicModelReturnsClaudeService() {
        AiService service = router.resolveAiService("claude-sonnet-4-20250514");
        assertSame(claudeService, service,
                "Anthropic model should resolve to claudeService");
    }

    @Test
    void testResolveAiService_forOpenAiModelReturnsOpenAiService() {
        AiService service = router.resolveAiService("openai:gpt-4o");
        assertSame(openAiService, service,
                "OpenAI model (prefixed with 'openai:') should resolve to openAiService");
    }

    @Test
    void testResolveAiService_forOllamaModelReturnsOllamaService() {
        AiService service = router.resolveAiService("ollama:llama3.1");
        assertSame(ollamaService, service,
                "Ollama model (prefixed with 'ollama:') should resolve to ollamaService");
    }
}