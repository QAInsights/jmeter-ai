package org.qainsights.jmeter.ai.service.reasoning;

import com.openai.models.ReasoningEffort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link OpenAiReasoning}: effort mapping per model family and
 * the thinking toggle behavior of gpt-5.x models.
 */
class OpenAiReasoningTest {

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
    void noEffortForNonReasoningModels() {
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        assertEquals(Optional.empty(), OpenAiReasoning.effortFor(settings, "gpt-4o"));
        assertEquals(Optional.empty(), OpenAiReasoning.effortFor(settings, "gpt-4.1"));
        assertEquals(Optional.empty(), OpenAiReasoning.effortFor(settings, null));
        assertEquals(Optional.empty(), OpenAiReasoning.effortFor(settings, ""));
    }

    @Test
    void alwaysReasoningModelsUseChosenEffort() {
        ReasoningSettings settings = new ReasoningSettings(false, "low");
        assertEquals(Optional.of(ReasoningEffort.LOW), OpenAiReasoning.effortFor(settings, "o3"));
        assertEquals(Optional.of(ReasoningEffort.LOW), OpenAiReasoning.effortFor(settings, "o4-mini"));
        // non-dotted gpt-5 supports "minimal" too
        assertEquals(Optional.of(ReasoningEffort.LOW), OpenAiReasoning.effortFor(settings, "gpt-5"));
        assertEquals(Optional.of(ReasoningEffort.LOW), OpenAiReasoning.effortFor(settings, "gpt-5-mini"));
    }

    @Test
    void alwaysReasoningModelsDefaultToMedium() {
        ReasoningSettings settings = new ReasoningSettings(false, "medium");
        assertEquals(Optional.of(ReasoningEffort.MEDIUM), OpenAiReasoning.effortFor(settings, "o3"));
        // null settings also default to medium
        assertEquals(Optional.of(ReasoningEffort.MEDIUM), OpenAiReasoning.effortFor(null, "o3"));
    }

    @Test
    void invalidEffortFallsBackToMedium() {
        ReasoningSettings settings = new ReasoningSettings(false, "extreme");
        assertEquals(Optional.of(ReasoningEffort.MEDIUM), OpenAiReasoning.effortFor(settings, "o3"));
    }

    @Test
    void gpt5xToggleOffSendsNone() {
        ReasoningSettings settings = new ReasoningSettings(false, "high");
        assertEquals(Optional.of(ReasoningEffort.NONE), OpenAiReasoning.effortFor(settings, "gpt-5.1"));
    }

    @Test
    void unknownModelSendsNothing() {
        // gpt-9-turbo matches no shape regex and no catalog key - attach nothing.
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        assertEquals(Optional.empty(), OpenAiReasoning.effortFor(settings, "gpt-9-turbo"));
    }

    @Test
    void gpt5VariantInheritsFamilyShape() {
        // A future gpt-5.9 resolves to the gpt-5 catalog entry (prefix match):
        // reasoning yes, no "none" level -> always on, chosen effort applies
        // regardless of the toggle state.
        assertEquals(Optional.of(ReasoningEffort.HIGH),
                OpenAiReasoning.effortFor(new ReasoningSettings(false, "high"), "gpt-5.9-quasar"));
        assertEquals(Optional.of(ReasoningEffort.LOW),
                OpenAiReasoning.effortFor(new ReasoningSettings(true, "low"), "gpt-5.9-quasar"));
    }

    @Test
    void gpt5xToggleOnSendsChosenEffort() {
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        assertEquals(Optional.of(ReasoningEffort.HIGH), OpenAiReasoning.effortFor(settings, "gpt-5.1"));
        ReasoningSettings low = new ReasoningSettings(true, "low");
        assertEquals(Optional.of(ReasoningEffort.LOW), OpenAiReasoning.effortFor(low, "gpt-5.1"));
    }

    @Test
    void toEffortMapping() {
        assertEquals(ReasoningEffort.NONE, OpenAiReasoning.toEffort("none"));
        assertEquals(ReasoningEffort.MINIMAL, OpenAiReasoning.toEffort("minimal"));
        assertEquals(ReasoningEffort.LOW, OpenAiReasoning.toEffort("low"));
        assertEquals(ReasoningEffort.MEDIUM, OpenAiReasoning.toEffort("medium"));
        assertEquals(ReasoningEffort.HIGH, OpenAiReasoning.toEffort("high"));
        assertEquals(ReasoningEffort.XHIGH, OpenAiReasoning.toEffort("xhigh"));
        assertEquals(ReasoningEffort.MEDIUM, OpenAiReasoning.toEffort("bogus"));
        assertEquals(ReasoningEffort.MEDIUM, OpenAiReasoning.toEffort(null));
    }
}
