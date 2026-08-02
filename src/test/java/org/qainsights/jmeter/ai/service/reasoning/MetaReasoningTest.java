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
 * Unit tests for {@link MetaReasoning}: effort levels for Muse Spark come from
 * the vendored models.dev data (minimal / low / medium / high / xhigh).
 */
class MetaReasoningTest {

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
    void museSparkHonorsChosenEffort() {
        assertEquals(Optional.of(ReasoningEffort.MINIMAL),
                MetaReasoning.effortFor(new ReasoningSettings(false, "minimal"), "muse-spark-1.1"));
        assertEquals(Optional.of(ReasoningEffort.LOW),
                MetaReasoning.effortFor(new ReasoningSettings(false, "low"), "muse-spark-1.1"));
        assertEquals(Optional.of(ReasoningEffort.HIGH),
                MetaReasoning.effortFor(new ReasoningSettings(false, "high"), "muse-spark-1.1"));
        assertEquals(Optional.of(ReasoningEffort.XHIGH),
                MetaReasoning.effortFor(new ReasoningSettings(false, "xhigh"), "muse-spark-1.1"));
    }

    @Test
    void unsupportedLevelFallsBackToMedium() {
        assertEquals(Optional.of(ReasoningEffort.MEDIUM),
                MetaReasoning.effortFor(new ReasoningSettings(false, "extreme"), "muse-spark-1.1"));
        assertEquals(Optional.of(ReasoningEffort.MEDIUM),
                MetaReasoning.effortFor(null, "muse-spark-1.1"));
    }

    @Test
    void noEffortForUnknownModels() {
        ReasoningSettings settings = new ReasoningSettings(false, "low");
        assertEquals(Optional.empty(), MetaReasoning.effortFor(settings, "muse-large"));
        assertEquals(Optional.empty(), MetaReasoning.effortFor(settings, null));
    }
}
