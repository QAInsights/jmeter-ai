package org.qainsights.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.service.AiServiceHolder;
import org.qainsights.jmeter.ai.service.ClaudeCodeAiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.CodexAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;

/**
 * Routing for the subscription CLI providers: the {@code codex:} and
 * {@code claude-code:} prefixes reach their own services, the API-backed OpenAI
 * and Claude services are never touched by them, and a CLI failure surfaces as a
 * readable message rather than an exception in the chat.
 */
@ExtendWith(MockitoExtension.class)
class AiResponseRouterCliProviderTest {

    @Mock
    private CodexAiService codexService;

    @Mock
    private ClaudeCodeAiService claudeCodeService;

    @Mock
    private OpenAiService openAiService;

    @Mock
    private ClaudeService claudeService;

    private AiResponseRouter router;
    private final List<String> history = List.of("test prompt");

    @BeforeEach
    void setUp() {
        AiServiceHolder holder = new AiServiceHolder();
        holder.setOpenAiService(openAiService);
        holder.setClaudeService(claudeService);
        holder.setCodexService(codexService);
        holder.setClaudeCodeService(claudeCodeService);
        router = new AiResponseRouter(holder);
    }

    @Test
    void codexPrefixRoutesToTheCodexServiceOnly() {
        when(codexService.generateResponse(anyList(), anyString())).thenReturn("codex answer");

        assertEquals("codex answer", router.getAiResponse("codex:default", history));

        verify(codexService).generateResponse(anyList(), anyString());
        verify(openAiService, never()).generateResponse(anyList(), anyString());
        verify(claudeService, never()).generateResponse(anyList(), anyString());
    }

    @Test
    void claudeCodePrefixRoutesToTheClaudeCodeServiceOnly() {
        when(claudeCodeService.generateResponse(anyList(), anyString())).thenReturn("claude code answer");

        assertEquals("claude code answer", router.getAiResponse("claude-code:default", history));

        verify(claudeCodeService).generateResponse(anyList(), anyString());
        verify(claudeService, never()).generateResponse(anyList(), anyString());
    }

    @Test
    void aCliFailureIsShownAsAMessageNotAnException() {
        when(codexService.generateResponse(anyList(), anyString()))
                .thenThrow(new CliProviderException("Codex is not signed in."));

        String response = router.getAiResponse("codex:default", history);

        assertTrue(response.contains("not signed in"), response);
    }

    @Test
    void resolveAiServicePicksTheCliServices() {
        assertSame(codexService, router.resolveAiService("codex:default"));
        assertSame(claudeCodeService, router.resolveAiService("claude-code:default"));
        assertSame(openAiService, router.resolveAiService("openai:gpt-4o"));
        assertSame(claudeService, router.resolveAiService("claude-3-7-sonnet-latest"));
    }

    @Test
    void cliModelsAreAgentCapableAndDoNotFallThroughToTheAnthropicApi() {
        assertTrue(CommandDispatcher.isAgentCapableModel("codex:default"));
        assertTrue(CommandDispatcher.isAgentCapableModel("claude-code:default"));
        assertTrue(!CommandDispatcher.isClaudeModel("claude-code:default"));
        assertTrue(!CommandDispatcher.isClaudeModel("codex:default"));
    }

    @Test
    void agentModeWiresTheCliServices() {
        assertTrue(JMeterAgent.forService(new CodexAiService()) != null);
        assertTrue(JMeterAgent.forService(new ClaudeCodeAiService()) != null);
    }
}
