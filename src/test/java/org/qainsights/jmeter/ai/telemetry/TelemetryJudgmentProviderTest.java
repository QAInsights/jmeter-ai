package org.qainsights.jmeter.ai.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.JudgmentProvider;

class TelemetryJudgmentProviderTest {
    private TelemetryCounters counters;
    private TelemetryCounters previous;

    @BeforeEach
    void setUp() {
        counters = new TelemetryCounters();
        previous = Telemetry.swapCountersForTesting(counters);
    }

    @AfterEach
    void tearDown() {
        Telemetry.swapCountersForTesting(previous);
    }

    @Test
    void recordsSuccessfulChooseAndScoreAndDelegatesCapabilities() {
        StubProvider delegate = new StubProvider();
        TelemetryJudgmentProvider provider = new TelemetryJudgmentProvider(delegate, "typesafe", () -> 0L);

        assertSame(delegate.choice, provider.choose(null, "instructions", Map.of()));
        assertSame(delegate.score, provider.score(null, "instructions", Map.of()));
        assertEquals(delegate.capabilities(), provider.capabilities());
        assertEquals(Map.of("decide_ok_typesafe", 2), counters.snapshot());
    }

    @Test
    void rethrowsTheSameRuntimeExceptionAndRecordsItsCategory() {
        RuntimeException failure = new IllegalStateException("private error text");
        JudgmentProvider delegate = new StubProvider() {
            @Override
            public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
                throw failure;
            }
        };
        TelemetryJudgmentProvider provider = new TelemetryJudgmentProvider(delegate, "openai", () -> 0L);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> provider.choose(null, "instructions", Map.of())));
        assertEquals(Map.of("decide_fail_openai_other", 1), counters.snapshot());
    }

    @Test
    void recordsSlowCallsAboveTwoSeconds() {
        AtomicLong clock = new AtomicLong();
        TelemetryJudgmentProvider provider = new TelemetryJudgmentProvider(
                new StubProvider(), "openrouter", () -> clock.getAndAdd(2_000_000_001L));

        provider.choose(null, "instructions", Map.of());

        assertEquals(Map.of("decide_ok_openrouter", 1, "decide_slow_openrouter", 1), counters.snapshot());
    }

    private static class StubProvider implements JudgmentProvider {
        private final ChoiceAnswer choice = new ChoiceAnswer("a", Map.of("a", 1.0), 1.0);
        private final ScoreAnswer score = new ScoreAnswer(1.0, 0.9);

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.CHOICE, Capability.SCORE);
        }

        @Override
        public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
            return choice;
        }

        @Override
        public ScoreAnswer score(Object state, String instructions, Map<String, String> levels) {
            return score;
        }
    }
}
