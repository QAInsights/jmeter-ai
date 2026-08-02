package org.qainsights.jmeter.ai.service.reasoning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link BedrockThinking}: capability gating, the
 * additionalModelRequestFields document, and reasoning extraction.
 */
class BedrockThinkingTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    @Test
    void appliesOnlyForThinkingClaudeOnBedrock() {
        ReasoningSettings on = new ReasoningSettings(true, "medium");
        ReasoningSettings off = new ReasoningSettings(false, "medium");
        assertTrue(BedrockThinking.applies(on, "anthropic.claude-opus-4-6-v1"));
        assertTrue(BedrockThinking.applies(on, "anthropic.claude-fable-5"));
        assertFalse(BedrockThinking.applies(on, "anthropic.claude-3-5-sonnet-20241022-v2:0"));
        assertFalse(BedrockThinking.applies(on, "amazon.nova-pro-v1:0"));
        assertFalse(BedrockThinking.applies(off, "anthropic.claude-opus-4-6-v1"));
        assertFalse(BedrockThinking.applies(null, "anthropic.claude-opus-4-6-v1"));
    }

    @Test
    void budgetForHonorsEffort() {
        assertEquals(2048, BedrockThinking.budgetFor(
                new ReasoningSettings(true, "low"), "anthropic.claude-opus-4-6-v1"));
        assertEquals(16384, BedrockThinking.budgetFor(
                new ReasoningSettings(true, "high"), "anthropic.claude-opus-4-6-v1"));
        assertEquals(0, BedrockThinking.budgetFor(
                new ReasoningSettings(false, "high"), "anthropic.claude-opus-4-6-v1"));
        // adaptive-thinking Claude (fable) takes no budget
        assertEquals(0, BedrockThinking.budgetFor(
                new ReasoningSettings(true, "high"), "anthropic.claude-fable-5"));
    }

    @Test
    void additionalFieldsBuildsThinkingDocument() {
        Document doc = BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(true, "low"), "anthropic.claude-opus-4-6-v1");
        assertNotNull(doc);
        Document thinking = doc.asMap().get("thinking");
        assertNotNull(thinking);
        assertEquals("enabled", thinking.asMap().get("type").asString());
        assertEquals(2048L, thinking.asMap().get("budget_tokens").asNumber().longValue());
    }

    @Test
    void additionalFieldsAdaptiveDocumentForFable() {
        Document doc = BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(true, "medium"), "anthropic.claude-fable-5");
        assertNotNull(doc);
        Document thinking = doc.asMap().get("thinking");
        assertNotNull(thinking);
        assertEquals("adaptive", thinking.asMap().get("type").asString());
        assertNull(thinking.asMap().get("budget_tokens"), "adaptive thinking carries no budget");
    }

    @Test
    void additionalFieldsNullWhenNotApplicable() {
        assertNull(BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(false, "low"), "anthropic.claude-opus-4-6-v1"));
        assertNull(BedrockThinking.additionalFieldsFor(null, "anthropic.claude-opus-4-6-v1"));
        // display-only families send nothing even when reasoning-capable
        assertNull(BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(true, "high"), "deepseek.v3.2"));
        assertNull(BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(true, "high"), "qwen.qwen3-32b-v1:0"));
    }

    // ==================== OpenAI family on Bedrock ====================

    @Test
    void openAiFamilySendsReasoningEffort() {
        // gpt-oss: always-on, chosen effort applies regardless of the toggle
        Document doc = BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(false, "high"), "openai.gpt-oss-120b-1:0");
        assertNotNull(doc);
        assertEquals("high", doc.asMap().get("reasoning_effort").asString());
    }

    @Test
    void openAiFamilyToggleableModelsSendNoneWhenOff() {
        // gpt-5.x on Bedrock accepts "none"
        Document off = BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(false, "high"), "openai.gpt-5.4");
        assertEquals("none", off.asMap().get("reasoning_effort").asString());
        Document on = BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(true, "xhigh"), "openai.gpt-5.4");
        assertEquals("xhigh", on.asMap().get("reasoning_effort").asString());
    }

    @Test
    void openAiFamilyRegionPrefixedIdsResolve() {
        Document doc = BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(false, "medium"), "us.openai.gpt-oss-20b-1:0");
        assertNotNull(doc);
        assertEquals("medium", doc.asMap().get("reasoning_effort").asString());
    }

    // ==================== Amazon Nova 2 ====================

    @Test
    void novaSendsReasoningConfigWhenEnabled() {
        Document doc = BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(true, "low"), "amazon.nova-2-lite-v1:0");
        assertNotNull(doc);
        Document config = doc.asMap().get("reasoningConfig");
        assertNotNull(config);
        assertEquals("enabled", config.asMap().get("type").asString());
        assertEquals("low", config.asMap().get("maxReasoningEffort").asString());
    }

    @Test
    void novaSendsNothingWhenDisabled() {
        // reasoning is disabled by default on Nova 2 - nothing to send
        assertNull(BedrockThinking.additionalFieldsFor(
                new ReasoningSettings(false, "low"), "amazon.nova-2-lite-v1:0"));
    }

    // ==================== dropsTemperature ====================

    @Test
    void dropsTemperatureRules() {
        ReasoningSettings on = new ReasoningSettings(true, "high");
        ReasoningSettings off = new ReasoningSettings(false, "medium");
        // any thinking-enabled Claude request drops temperature
        assertTrue(BedrockThinking.dropsTemperature(on, "anthropic.claude-opus-4-6-v1"));
        assertTrue(BedrockThinking.dropsTemperature(on, "anthropic.claude-fable-5"));
        assertFalse(BedrockThinking.dropsTemperature(off, "anthropic.claude-opus-4-6-v1"));
        // Nova 2 only at high effort (AWS requirement)
        assertTrue(BedrockThinking.dropsTemperature(on, "amazon.nova-2-lite-v1:0"));
        assertFalse(BedrockThinking.dropsTemperature(
                new ReasoningSettings(true, "medium"), "amazon.nova-2-lite-v1:0"));
        // everything else keeps temperature
        assertFalse(BedrockThinking.dropsTemperature(on, "openai.gpt-oss-120b-1:0"));
        assertFalse(BedrockThinking.dropsTemperature(on, "deepseek.v3.2"));
    }

    @Test
    void extractReasoningConcatenatesReasoningBlocks() {
        ConverseResponse response = ConverseResponse.builder()
                .output(ConverseOutput.builder()
                        .message(Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(
                                        ContentBlock.fromReasoningContent(
                                                software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock
                                                        .fromReasoningText(ReasoningTextBlock.builder()
                                                                .text("deep thought").build())),
                                        ContentBlock.fromText("the answer"))
                                .build())
                        .build())
                .build();
        assertEquals("deep thought", BedrockThinking.extractReasoning(response));
    }

    @Test
    void extractReasoningNullWhenNoReasoning() {
        ConverseResponse response = ConverseResponse.builder()
                .output(ConverseOutput.builder()
                        .message(Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(ContentBlock.fromText("just text"))
                                .build())
                        .build())
                .build();
        assertNull(BedrockThinking.extractReasoning(response));
        assertNull(BedrockThinking.extractReasoning(null));
    }
}
