package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.service.AiServiceHolder;
import org.qainsights.jmeter.ai.service.AppleFoundationAiService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiResponseRouterAppleTest {
    private AppleFoundationAiService appleService;
    private AiResponseRouter router;
    private final List<String> history = List.of("question");

    @BeforeEach
    void setUp() {
        appleService = mock(AppleFoundationAiService.class);
        AiServiceHolder holder = new AiServiceHolder();
        holder.setAppleFoundationAiService(appleService);
        router = new AiResponseRouter(holder);
    }

    @Test
    void nonStreamingResponseDispatchesToAppleAndReturnsChatErrorText() {
        when(appleService.generateResponse(history, "on-device")).thenReturn("answer");
        assertEquals("answer", router.getAiResponse("apple:on-device", history));
        verify(appleService).generateResponse(history, "on-device");

        when(appleService.generateResponse(history, "on-device"))
                .thenThrow(new org.qainsights.jmeter.ai.service.AppleFoundationException("not available", "unavailable"));
        assertEquals("Error: not available", router.getAiResponse("apple:on-device", history));
    }

    @Test
    void streamingResponseDispatchesToApple() {
        Runnable expectedCancel = () -> {};
        when(appleService.generateStreamResponse(eq(history), eq("on-device"), any(), any(), any()))
                .thenReturn(expectedCancel);

        Runnable cancel = router.generateStreamResponse("apple:on-device", history,
                ignored -> {}, () -> {}, ignored -> {});

        assertSame(expectedCancel, cancel);
        verify(appleService).generateStreamResponse(eq(history), eq("on-device"), any(), any(), any());
    }

    @Test
    void resolvesAppleServiceAndKeepsItOutOfAgentMode() {
        assertSame(appleService, router.resolveAiService("apple:on-device"));
        assertNull(JMeterAgent.forService(appleService));
        assertFalse(CommandDispatcher.isAgentCapableModel("apple:on-device"));
        assertFalse(CommandDispatcher.shouldUseAgent(true, true, "apple:on-device"));
    }
}
