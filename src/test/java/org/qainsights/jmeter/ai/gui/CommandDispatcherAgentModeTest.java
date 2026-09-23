package org.qainsights.jmeter.ai.gui;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.agent.AgentRunListeners;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the agent-mode dispatch path ({@link CommandDispatcher} ->
 * {@link AgentCommandRunner}): the unsupported-service message, the step-limit
 * suffix, and the three error branches (CLI provider failure, rate limit, generic
 * failure with plain-chat fallback).
 *
 * <p>The agent runs on a SwingWorker thread, where a {@code MockedStatic} of
 * {@code JMeterAgent} would not be visible, so the agent is injected through the
 * package-private {@link CommandDispatcher} constructor instead.
 */
@ExtendWith(MockitoExtension.class)
class CommandDispatcherAgentModeTest {

    private static final long WORKER_TIMEOUT_MS = 5_000;
    private static final String MESSAGE = "add a thread group";

    private static MockedStatic<AiConfig> aiConfigMockedStatic;
    private final AtomicReference<JMeterAgent> resolvedAgent = new AtomicReference<>();
    private final AtomicReference<AiService> factoryInput = new AtomicReference<>();

    @Mock
    private CommandCallback cb;
    @Mock
    private AiService service;
    @Mock
    private JMeterAgent agent;

    private CommandDispatcher commandDispatcher;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
    }

    @AfterAll
    static void tearDownAll() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @BeforeEach
    void setUp() {
        Function<AiService, JMeterAgent> agentFactory = resolved -> {
            factoryInput.set(resolved);
            return resolvedAgent.get();
        };
        commandDispatcher = new CommandDispatcher(cb, agentFactory);
        aiConfigMockedStatic.when(AiConfig::isStreamingEnabled).thenReturn(false);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(JMeterAgent.ENABLED_KEY, "false")).thenReturn("true");
        when(cb.isAgentModeSelected()).thenReturn(true);
        when(cb.getSelectedModel()).thenReturn("openai:gpt-4o");
        when(cb.getConversationHistory()).thenReturn(new ArrayList<>(List.of(MESSAGE)));
        lenient().when(cb.resolveAttachmentMarkers(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(cb.resolveAiService("openai:gpt-4o")).thenReturn(service);
    }

    private static RateLimitException openAi429(String message) {
        return RateLimitException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder().message(message).code("quota").param("").type("rate_limit").build())
                .build();
    }

    private void agentResolvesTo(JMeterAgent resolved) {
        resolvedAgent.set(resolved);
    }

    private void agentRunReturns(AgentLoop.AgentResult result) {
        when(agent.run(eq(MESSAGE), eq(MESSAGE), anyList(), any(AgentRunListeners.class))).thenReturn(result);
    }

    private void agentRunThrows(RuntimeException error) {
        when(agent.run(eq(MESSAGE), eq(MESSAGE), anyList(), any(AgentRunListeners.class))).thenThrow(error);
    }

    private static AgentLoop.AgentResult result(boolean completed, String finalText) {
        AgentLoop.AgentResult result = mock(AgentLoop.AgentResult.class);
        when(result.isCompleted()).thenReturn(completed);
        when(result.getFinalText()).thenReturn(finalText);
        return result;
    }

    private String awaitWorkerSuccess() {
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(cb, timeout(WORKER_TIMEOUT_MS)).onWorkerSuccess(response.capture());
        verify(cb, timeout(WORKER_TIMEOUT_MS)).addToConversationHistory(response.getValue());
        assertSame(service, factoryInput.get(), "agent must be wired for the resolved AiService");
        return response.getValue();
    }

    // ==================== Dispatch wiring ====================

    @Test
    void agentModeSkipsPlainChatAndDisablesInput() {
        agentResolvesTo(agent);
        agentRunReturns(result(true, "Added."));

        commandDispatcher.dispatch(MESSAGE);
        awaitWorkerSuccess();

        verify(cb).setLastCommandType("NONE");
        verify(cb).setInputEnabled(false);
        verify(cb).removeLoadingIndicator();
        verify(cb, never()).getAiStreamResponse(any(), any(), any(), any());
        verify(cb, never()).getAiResponse(anyString());
    }

    @Test
    void completedRunReturnsFinalTextVerbatim() {
        agentResolvesTo(agent);
        agentRunReturns(result(true, "Thread group added."));

        commandDispatcher.dispatch(MESSAGE);

        assertEquals("Thread group added.", awaitWorkerSuccess());
    }

    @Test
    void completedRunWithEmptyTextReportsDone() {
        agentResolvesTo(agent);
        agentRunReturns(result(true, ""));

        commandDispatcher.dispatch(MESSAGE);

        assertEquals("Done.", awaitWorkerSuccess());
    }

    // ==================== Unsupported service ====================

    @Test
    void nullAgentSurfacesUnsupportedModelMessageWithoutFallback() {
        agentResolvesTo(null);

        commandDispatcher.dispatch(MESSAGE);
        String response = awaitWorkerSuccess();

        assertTrue(response.startsWith("Agent mode currently supports"), response);
        assertTrue(response.contains("Select one of those and retry."), response);
        verify(cb, never()).getAiResponse(anyString());
    }

    // ==================== Step limit ====================

    @Test
    void incompleteRunAppendsStepLimitSuffixToPartialText() {
        agentResolvesTo(agent);
        agentRunReturns(result(false, "Partial progress"));

        commandDispatcher.dispatch(MESSAGE);

        assertEquals("Partial progress\n\n[Agent stopped after reaching the step limit.]", awaitWorkerSuccess());
    }

    @Test
    void incompleteRunWithNoTextReportsOnlyStepLimit() {
        agentResolvesTo(agent);
        agentRunReturns(result(false, ""));

        commandDispatcher.dispatch(MESSAGE);

        assertEquals("[Agent stopped after reaching the step limit.]", awaitWorkerSuccess());
    }

    // ==================== CLI provider failure ====================

    @Test
    void cliProviderExceptionSurfacesProviderMessageWithoutFallback() {
        agentResolvesTo(agent);
        agentRunThrows(new CliProviderException("codex CLI is not signed in"));

        commandDispatcher.dispatch(MESSAGE);

        assertEquals("Error: codex CLI is not signed in", awaitWorkerSuccess());
        verify(cb, never()).getAiResponse(anyString());
        verify(cb, never()).appendToolActivity(anyString());
        verify(cb, never()).onWorkerError(anyString(), any(), anyString());
    }

    // ==================== Rate limit ====================

    @Test
    void rateLimitedFailureSurfacesAgentAdviceWithoutFallback() {
        agentResolvesTo(agent);
        agentRunThrows(new RuntimeException("agent failed", openAi429("Token quota exceeded")));

        commandDispatcher.dispatch(MESSAGE);
        String response = awaitWorkerSuccess();

        assertTrue(response.startsWith("Error: Rate limit or token quota exceeded (HTTP 429): Token quota exceeded."),
                response);
        assertTrue(response.contains("jmeter.ai.agent.max.tokens"), response);
        verify(cb, never()).getAiResponse(anyString());
        verify(cb, never()).appendToolActivity(anyString());
    }

    // ==================== Generic failure -> plain chat fallback ====================

    @Test
    void genericFailureFallsBackToPlainChatAnswer() {
        agentResolvesTo(agent);
        agentRunThrows(new IllegalStateException("tool registry broken"));
        when(cb.getAiResponse(MESSAGE)).thenReturn("Plain answer");

        commandDispatcher.dispatch(MESSAGE);

        assertEquals("Plain answer", awaitWorkerSuccess());
        verify(cb, timeout(WORKER_TIMEOUT_MS)).appendToolActivity(
                "[Agent error: tool registry broken - falling back to a plain answer.]");
        verify(cb, never()).onWorkerError(anyString(), any(), anyString());
    }

    @Test
    void fallbackFailureIsReportedAsWorkerError() {
        agentResolvesTo(agent);
        agentRunThrows(new IllegalStateException("tool registry broken"));
        when(cb.getAiResponse(MESSAGE)).thenThrow(new IllegalStateException("chat also failed"));

        commandDispatcher.dispatch(MESSAGE);

        verify(cb, timeout(WORKER_TIMEOUT_MS)).onWorkerError(eq("Error running the agent"), any(),
                eq("Sorry, I encountered an error while running the agent. Please try again."));
        verify(cb, never()).onWorkerSuccess(anyString());
    }

    // ==================== Streaming final text ====================

    @Test
    void streamingEnabledReplaysErrorMessageAsTokensThenCompletes() {
        aiConfigMockedStatic.when(AiConfig::isStreamingEnabled).thenReturn(true);
        agentResolvesTo(agent);
        agentRunThrows(new CliProviderException("claude CLI missing"));

        commandDispatcher.dispatch(MESSAGE);

        verify(cb, timeout(WORKER_TIMEOUT_MS)).onStreamComplete("Error: claude CLI missing");
        verify(cb, timeout(WORKER_TIMEOUT_MS)).appendStreamToken("Error: ");
        verify(cb, timeout(WORKER_TIMEOUT_MS)).appendStreamToken("missing");
        verify(cb, never()).onWorkerSuccess(anyString());
        verify(cb, never()).getAiResponse(anyString());
    }
}
