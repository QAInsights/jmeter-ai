package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the reasoning-related default methods on {@link AiService}:
 * the 6-arg streaming overload delegation, the setReasoningSettings no-op, and
 * consumeLastReasoning.
 */
class AiServiceReasoningTest {

    /** Minimal service that only implements the original 5-arg streaming method. */
    private static class StubService implements AiService {
        final AtomicBoolean fiveArgCalled = new AtomicBoolean(false);

        @Override
        public String generateResponse(List<String> conversation) {
            return "ok";
        }

        @Override
        public String generateResponse(List<String> conversation, String model) {
            return "ok";
        }

        @Override
        public String getName() {
            return "Stub";
        }

        @Override
        public Runnable generateStreamResponse(List<String> conversation, String model,
                java.util.function.Consumer<String> tokenConsumer, Runnable onComplete,
                java.util.function.Consumer<Exception> onError) {
            fiveArgCalled.set(true);
            return () -> {};
        }
    }

    @Test
    void sixArgOverloadDelegatesToFiveArg() {
        StubService service = new StubService();
        Runnable cancel = service.generateStreamResponse(
                Collections.singletonList("hi"), "model",
                token -> {}, reasoning -> {}, () -> {}, e -> {});
        assertNotNull(cancel);
        assertTrue(service.fiveArgCalled.get(),
                "6-arg overload must delegate to the 5-arg method");
    }

    @Test
    void setReasoningSettingsIsNoOpByDefault() {
        StubService service = new StubService();
        assertDoesNotThrow(() -> service.setReasoningSettings(
                new org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings(true, "low")));
    }

    @Test
    void consumeLastReasoningDefaultsToNull() {
        StubService service = new StubService();
        assertNull(service.consumeLastReasoning());
    }
}
