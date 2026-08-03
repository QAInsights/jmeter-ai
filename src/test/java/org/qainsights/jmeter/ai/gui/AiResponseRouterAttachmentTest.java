package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.AiServiceHolder;
import org.qainsights.jmeter.ai.service.BedrockAiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.service.GrokAiService;
import org.qainsights.jmeter.ai.service.MetaMuseAiService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.attach.AttachmentRegistry;
import org.qainsights.jmeter.ai.service.attach.FileContentPreparer;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the attachment-marker resolution in {@link AiResponseRouter}:
 * prepared content is substituted into the conversation before it is forwarded
 * to a provider service.
 */
@ExtendWith(MockitoExtension.class)
class AiResponseRouterAttachmentTest {

    @Mock private ClaudeService claudeService;
    @Mock private OpenAiService openAiService;
    @Mock private OllamaAiService ollamaService;
    @Mock private DeepseekAiService deepseekService;
    @Mock private GoogleAiService googleService;
    @Mock private GrokAiService grokService;
    @Mock private MetaMuseAiService metaMuseService;
    @Mock private BedrockAiService bedrockService;

    private MockedStatic<AiConfig> aiConfigMockedStatic;
    private AiResponseRouter router;
    private AttachmentRegistry registry;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        AiServiceHolder holder = new AiServiceHolder();
        holder.setClaudeService(claudeService);
        holder.setOpenAiService(openAiService);
        holder.setOllamaService(ollamaService);
        holder.setDeepseekService(deepseekService);
        holder.setGoogleService(googleService);
        holder.setGrokService(grokService);
        holder.setMetaMuseService(metaMuseService);
        holder.setBedrockService(bedrockService);
        router = new AiResponseRouter(holder);
        registry = new AttachmentRegistry();
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    @Test
    void markersAreResolvedBeforeForwarding() {
        registry.register("notes.txt", "file body", FileContentPreparer.Mode.SMART);
        router.setAttachmentRegistry(registry);
        when(claudeService.generateResponse(anyList())).thenReturn("answer");

        router.getAiResponse("claude-sonnet-4-6", List.of("check this [file:f1]"));

        verify(claudeService).generateResponse(argThat(conversation ->
                conversation.size() == 1
                        && conversation.get(0).contains("<attached file=\"notes.txt\"")
                        && conversation.get(0).contains("file body")
                        && !conversation.get(0).contains("[file:")));
    }

    @Test
    void noRegistryLeavesConversationUntouched() {
        when(claudeService.generateResponse(anyList())).thenReturn("answer");

        router.getAiResponse("claude-sonnet-4-6", List.of("check this [file:f1]"));

        verify(claudeService).generateResponse(List.of("check this [file:f1]"));
    }

    @Test
    void streamPathResolvesMarkers() {
        registry.register("notes.txt", "file body", FileContentPreparer.Mode.SMART);
        router.setAttachmentRegistry(registry);
        when(claudeService.generateStreamResponse(anyList(), anyString(),
                any(), any(), any(), any())).thenReturn(() -> {});

        router.generateStreamResponse("claude-sonnet-4-6", List.of("check this [file:f1]"),
                token -> {}, () -> {}, e -> {});

        verify(claudeService).generateStreamResponse(argThat(conversation ->
                conversation.size() == 1 && !conversation.get(0).contains("[file:")),
                anyString(), any(), any(), any(), any());
    }
}
