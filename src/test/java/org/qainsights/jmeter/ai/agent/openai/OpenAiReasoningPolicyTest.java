package org.qainsights.jmeter.ai.agent.openai;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.openai.models.ReasoningEffort;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link OpenAiReasoningPolicy}. */
class OpenAiReasoningPolicyTest {

    @Test
    void gpt5DottedMinorModels_mustDisableReasoningForToolCalling() {
        assertEquals(Optional.of(ReasoningEffort.NONE), OpenAiReasoningPolicy.forToolCalling("gpt-5.1"));
        assertEquals(Optional.of(ReasoningEffort.NONE), OpenAiReasoningPolicy.forToolCalling("gpt-5.6-terra"));
        assertEquals(Optional.of(ReasoningEffort.NONE), OpenAiReasoningPolicy.forToolCalling("gpt-5.6-sol"));
        assertEquals(Optional.of(ReasoningEffort.NONE), OpenAiReasoningPolicy.forToolCalling("gpt-5.1-codex"));
    }

    @Test
    void originalGpt5Line_isLeftAlone() {
        assertFalse(OpenAiReasoningPolicy.forToolCalling("gpt-5").isPresent());
        assertFalse(OpenAiReasoningPolicy.forToolCalling("gpt-5-mini").isPresent());
        assertFalse(OpenAiReasoningPolicy.forToolCalling("gpt-5-nano").isPresent());
    }

    @Test
    void nonReasoningAndOSeriesModels_areLeftAlone() {
        assertFalse(OpenAiReasoningPolicy.forToolCalling("gpt-4o").isPresent());
        assertFalse(OpenAiReasoningPolicy.forToolCalling("gpt-4.1").isPresent());
        assertFalse(OpenAiReasoningPolicy.forToolCalling("o1").isPresent());
        assertFalse(OpenAiReasoningPolicy.forToolCalling("o3-mini").isPresent());
        assertFalse(OpenAiReasoningPolicy.forToolCalling("o4-mini").isPresent());
    }

    @Test
    void modelIdIsMatchedCaseInsensitivelyAndTrimmed() {
        assertTrue(OpenAiReasoningPolicy.requiresDisabledReasoning("  GPT-5.6-Terra  "));
    }

    @Test
    void nullOrEmptyModel_isLeftAlone() {
        assertFalse(OpenAiReasoningPolicy.requiresDisabledReasoning(null));
        assertFalse(OpenAiReasoningPolicy.requiresDisabledReasoning(""));
        assertFalse(OpenAiReasoningPolicy.forToolCalling(null).isPresent());
    }
}
