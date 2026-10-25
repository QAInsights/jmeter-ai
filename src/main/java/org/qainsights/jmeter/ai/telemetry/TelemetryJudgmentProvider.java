package org.qainsights.jmeter.ai.telemetry;

import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.qainsights.jmeter.ai.service.JudgmentProvider;
import org.qainsights.jmeter.ai.utils.ProviderErrors;

/** Records aggregate outcomes and durations for Decisions calls without inspecting their payloads. */
public final class TelemetryJudgmentProvider implements JudgmentProvider {
    private static final long SLOW_CALL_NANOS = 2_000_000_000L;

    private final JudgmentProvider delegate;
    private final String backend;
    private final LongSupplier nanoTime;

    public TelemetryJudgmentProvider(JudgmentProvider delegate, String backend) {
        this(delegate, backend, System::nanoTime);
    }

    TelemetryJudgmentProvider(JudgmentProvider delegate, String backend, LongSupplier nanoTime) {
        this.delegate = delegate;
        this.backend = backend;
        this.nanoTime = nanoTime;
    }

    @Override
    public Set<Capability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
        return measured(() -> delegate.choose(state, instructions, criteria));
    }

    @Override
    public ScoreAnswer score(Object state, String instructions, Map<String, String> levels) {
        return measured(() -> delegate.score(state, instructions, levels));
    }

    private <T> T measured(java.util.function.Supplier<T> call) {
        long started = nanoTime.getAsLong();
        try {
            T result = call.get();
            Telemetry.recordDecisionResult(backend, "ok", null);
            return result;
        } catch (RuntimeException e) {
            Telemetry.recordDecisionResult(backend, "fail", ProviderErrors.category(e));
            throw e;
        } finally {
            if (nanoTime.getAsLong() - started > SLOW_CALL_NANOS) {
                Telemetry.recordDecisionResult(backend, "slow", null);
            }
        }
    }
}
