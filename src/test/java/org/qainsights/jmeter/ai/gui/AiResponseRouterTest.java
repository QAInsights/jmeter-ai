package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiResponseRouterTest {

    @Mock
    private ClaudeService claudeService;

    @Mock
    private OpenAiService openAiService;

    @Mock
    private OllamaAiService ollamaService;

    @Mock
    private DeepseekAiService deepseekService;

    @Mock
    private GoogleAiService googleService;

    private AiResponseRouter router;
    private final List<String> history = Collections.singletonList("test prompt");

    @BeforeEach
    void setUp() {
        router = new AiResponseRouter(claudeService, openAiService, ollamaService, deepseekService, googleService, null);
    }

    @Test
    void testGetAiResponse_NullModel() {
        when(claudeService.getCurrentModel()).thenReturn("claude-sonnet");
        when(claudeService.generateResponse(history)).thenReturn("claude response");

        String response = router.getAiResponse(null, history);

        assertEquals("claude response", response);
        verify(claudeService).generateResponse(history);
    }

    @Test
    void testGetAiResponse_OpenAi() {
        when(openAiService.generateResponse(history)).thenReturn("openai response");

        String response = router.getAiResponse("openai:gpt-4o", history);

        assertEquals("openai response", response);
        verify(openAiService).setModel("gpt-4o");
        verify(openAiService).generateResponse(history);
    }

    @Test
    void testGetAiResponse_Ollama() {
        when(ollamaService.generateResponse(history)).thenReturn("ollama response");

        String response = router.getAiResponse("ollama:llama3.1", history);

        assertEquals("ollama response", response);
        verify(ollamaService).setModel("llama3.1");
        verify(ollamaService).generateResponse(history);
    }

    @Test
    void testGetAiResponse_Deepseek() {
        when(deepseekService.generateResponse(history)).thenReturn("deepseek response");

        String response = router.getAiResponse("deepseek:deepseek-chat", history);

        assertEquals("deepseek response", response);
        verify(deepseekService).setModel("deepseek-chat");
        verify(deepseekService).generateResponse(history);
    }

    @Test
    void testGetAiResponse_Google_NotNull() {
        when(googleService.generateResponse(history)).thenReturn("google response");

        String response = router.getAiResponse("google:gemini-1.5", history);

        assertEquals("google response", response);
        verify(googleService).setModel("gemini-1.5");
        verify(googleService).generateResponse(history);
    }

    @Test
    void testGetAiResponse_Google_NullService() {
        AiResponseRouter nullGoogleRouter = new AiResponseRouter(claudeService, openAiService, ollamaService, deepseekService, null, null);
        String response = nullGoogleRouter.getAiResponse("google:gemini-1.5", history);

        assertTrue(response.contains("Google Gemini service not configured"));
    }

    @Test
    void testGetAiResponse_Anthropic() {
        when(claudeService.generateResponse(history)).thenReturn("anthropic response");

        String response = router.getAiResponse("claude-sonnet-4", history);

        assertEquals("anthropic response", response);
        verify(claudeService).setModel("claude-sonnet-4");
        verify(claudeService).generateResponse(history);
    }

    @Test
    void testResolveAiService() {
        assertEquals(openAiService, router.resolveAiService("openai:gpt-4o"));
        assertEquals(ollamaService, router.resolveAiService("ollama:llama3.1"));
        assertEquals(deepseekService, router.resolveAiService("deepseek:deepseek-chat"));
        assertEquals(googleService, router.resolveAiService("google:gemini-1.5"));
        assertEquals(claudeService, router.resolveAiService("claude-sonnet-4"));
    }

    @Test
    void testGenerateStreamResponse_Google_NullService() {
        AiResponseRouter nullGoogleRouter = new AiResponseRouter(claudeService, openAiService, ollamaService, deepseekService, null, null);
        Runnable cancelHandle = nullGoogleRouter.generateStreamResponse("google:gemini-1.5", history, token -> {}, () -> {}, err -> {});
        assertNotNull(cancelHandle);
        assertDoesNotThrow(cancelHandle::run);
    }
}
