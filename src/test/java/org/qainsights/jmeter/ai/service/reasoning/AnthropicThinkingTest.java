package org.qainsights.jmeter.ai.service.reasoning;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link AnthropicThinking}: when thinking applies, how it lands
 * on the request params, max-token bumping, and response block extraction.
 */
class AnthropicThinkingTest {

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

    // ==================== applies ====================

    @Test
    void appliesOnlyWhenToggleOnAndModelCapable() {
        ReasoningSettings on = new ReasoningSettings(true, "medium");
        ReasoningSettings off = new ReasoningSettings(false, "medium");
        assertTrue(AnthropicThinking.applies(on, "claude-sonnet-4-6"));
        assertFalse(AnthropicThinking.applies(on, "claude-3-5-sonnet-20241022"));
        assertFalse(AnthropicThinking.applies(off, "claude-sonnet-4-6"));
        assertFalse(AnthropicThinking.applies(null, "claude-sonnet-4-6"));
    }

    // ==================== applyTo ====================

    @Test
    void applyToSetsThinkingConfigWithBudget() {
        ReasoningSettings settings = new ReasoningSettings(true, "low");
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .addUserMessage("hi");

        long budget = AnthropicThinking.applyTo(builder, settings, "claude-sonnet-4-6");

        assertEquals(2048, budget);
        MessageCreateParams params = builder.build();
        assertTrue(params.thinking().isPresent());
        assertEquals(2048, params.thinking().get().enabled().orElseThrow().budgetTokens());
    }

    @Test
    void applyToSkipsWhenNotApplicable() {
        ReasoningSettings settings = new ReasoningSettings(false, "low");
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .addUserMessage("hi");

        assertEquals(0, AnthropicThinking.applyTo(builder, settings, "claude-sonnet-4-6"));
        assertTrue(builder.build().thinking().isEmpty());
    }

    @Test
    void applyToSendsAdaptiveConfigForFable() {
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model("claude-fable-5")
                .maxTokens(4096)
                .addUserMessage("hi");

        long budget = AnthropicThinking.applyTo(builder, settings, "claude-fable-5");

        assertEquals(0, budget, "adaptive thinking carries no budget");
        MessageCreateParams params = builder.build();
        assertTrue(params.thinking().isPresent());
        assertTrue(params.thinking().get().adaptive().isPresent(),
                "fable must get the adaptive thinking config");
        assertEquals(com.anthropic.models.messages.ThinkingConfigAdaptive.Display.SUMMARIZED,
                params.thinking().get().adaptive().get().display().orElseThrow());
        // the chosen effort rides along via output_config
        assertEquals(com.anthropic.models.messages.OutputConfig.Effort.HIGH,
                params.outputConfig().orElseThrow().effort().orElseThrow());
    }

    @Test
    void toOutputEffortMapping() {
        assertEquals(com.anthropic.models.messages.OutputConfig.Effort.LOW,
                AnthropicThinking.toOutputEffort("low"));
        assertEquals(com.anthropic.models.messages.OutputConfig.Effort.MEDIUM,
                AnthropicThinking.toOutputEffort("medium"));
        assertEquals(com.anthropic.models.messages.OutputConfig.Effort.HIGH,
                AnthropicThinking.toOutputEffort("high"));
        assertEquals(com.anthropic.models.messages.OutputConfig.Effort.XHIGH,
                AnthropicThinking.toOutputEffort("xhigh"));
        assertEquals(com.anthropic.models.messages.OutputConfig.Effort.MAX,
                AnthropicThinking.toOutputEffort("max"));
        assertNull(AnthropicThinking.toOutputEffort("bogus"));
        assertNull(AnthropicThinking.toOutputEffort(null));
    }

    @Test
    void isAdaptiveThinkingModelDetection() {
        assertTrue(AnthropicThinking.isAdaptiveThinkingModel("claude-fable-5"));
        assertFalse(AnthropicThinking.isAdaptiveThinkingModel("claude-opus-4-8"));
        assertFalse(AnthropicThinking.isAdaptiveThinkingModel(null));
    }

    // ==================== effectiveMaxTokens ====================

    @Test
    void effectiveMaxTokensBumpsAboveBudget() {
        assertEquals(2048 + 1024, AnthropicThinking.effectiveMaxTokens(1024, 2048));
        assertEquals(16384, AnthropicThinking.effectiveMaxTokens(16384, 2048));
        assertEquals(1024, AnthropicThinking.effectiveMaxTokens(1024, 0));
    }

    // ==================== extraction ====================

    @Test
    void extractThinkingConcatenatesThinkingBlocks() {
        List<ContentBlock> content = List.of(
                ContentBlock.ofThinking(ThinkingBlock.builder()
                        .thinking("first thought").signature("sig1").build()),
                ContentBlock.ofThinking(ThinkingBlock.builder()
                        .thinking("second thought").signature("sig2").build()),
                ContentBlock.ofText(TextBlock.builder().citations(java.util.Collections.emptyList()).text("answer").build()));

        assertEquals("first thoughtsecond thought", AnthropicThinking.extractThinking(content));
    }

    @Test
    void extractThinkingReturnsNullWhenNoThinkingBlocks() {
        List<ContentBlock> content = List.of(
                ContentBlock.ofText(TextBlock.builder().citations(java.util.Collections.emptyList()).text("answer").build()));
        assertNull(AnthropicThinking.extractThinking(content));
    }

    @Test
    void extractTextSkipsThinkingBlocks() {
        List<ContentBlock> content = List.of(
                ContentBlock.ofThinking(ThinkingBlock.builder()
                        .thinking("thought").signature("sig").build()),
                ContentBlock.ofText(TextBlock.builder().citations(java.util.Collections.emptyList()).text("part one ").build()),
                ContentBlock.ofText(TextBlock.builder().citations(java.util.Collections.emptyList()).text("part two").build()));

        assertEquals("part one part two", AnthropicThinking.extractText(content));
    }
}

