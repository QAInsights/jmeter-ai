package org.qainsights.jmeter.ai.agent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.JudgmentProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeSafeAgentRequestRouterTest {

    @Test
    void confidentChoiceReturnsFocusedRoute() {
        JudgmentProvider provider = provider("CORRELATE", 0.9);
        TypeSafeAgentRequestRouter router = new TypeSafeAgentRequestRouter(provider, 0.75);

        AgentRequestRouter.Decision decision = router.route("find dynamic tokens");

        assertTrue(decision.isFocused());
        assertEquals(AgentRequestRouter.Route.CORRELATE, decision.route());
    }

    @Test
    void lowConfidenceAndComplexChoiceUseAllTools() {
        AgentRequestRouter.Decision low = new TypeSafeAgentRequestRouter(
                provider("EDIT_TEST_PLAN", 0.7), 0.75).route("change something");
        AgentRequestRouter.Decision complex = new TypeSafeAgentRequestRouter(
                provider("COMPLEX_OR_UNCLEAR", 0.99), 0.75).route("inspect, edit, run and save");

        assertEquals(AgentRequestRouter.Outcome.ALL_TOOLS, low.outcome());
        assertEquals(AgentRequestRouter.Outcome.ALL_TOOLS, complex.outcome());
    }

    @Test
    void unsupportedProviderAndProviderFailureAreUnavailable() {
        JudgmentProvider unsupported = new JudgmentProvider() {
            @Override
            public Set<Capability> capabilities() {
                return Set.of(Capability.SCORE);
            }

            @Override
            public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
                throw new AssertionError("must not be called");
            }
        };
        JudgmentProvider failing = new JudgmentProvider() {
            @Override
            public Set<Capability> capabilities() {
                return Set.of(Capability.CHOICE);
            }

            @Override
            public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
                throw new IllegalStateException("offline");
            }
        };

        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE,
                new TypeSafeAgentRequestRouter(unsupported, 0.75).route("hello").outcome());
        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE,
                new TypeSafeAgentRequestRouter(failing, 0.75).route("hello").outcome());
    }

    @Test
    void routeSendsOnlySanitizedCurrentMessageAndAgentMode() {
        AtomicReference<Object> capturedState = new AtomicReference<>();
        JudgmentProvider provider = new JudgmentProvider() {
            @Override
            public Set<Capability> capabilities() {
                return Set.of(Capability.CHOICE);
            }

            @Override
            public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
                capturedState.set(state);
                return new ChoiceAnswer("RUN_DIAGNOSE", distribution("RUN_DIAGNOSE", 0.9), 0.9);
            }
        };
        String message = "analyze [file:f1]\n<attached file=\"results.jtl\" mode=\"smart\">"
                + "SECRET-JTL-CONTENT</attached> then run";

        new TypeSafeAgentRequestRouter(provider, 0.75).route(message);

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) capturedState.get();
        String safeMessage = String.valueOf(state.get("message"));
        assertTrue(safeMessage.contains("[file attached]"));
        assertTrue(safeMessage.contains("[JTL attached]"));
        assertFalse(safeMessage.contains("SECRET-JTL-CONTENT"));
        assertEquals("true", state.get("agent_mode"));
        assertEquals(2, state.size());
    }

    @Test
    void sanitizeBoundsRegexInputWithoutLeakingTruncatedAttachmentBodies() {
        String message = "prefix<attached file=\"huge.log\" mode=\"raw\">"
                + "SECRET".repeat(10000) + "</attached>suffix";

        String coarse = TypeSafeAgentRequestRouter.coarseLimit(message);
        String safe = TypeSafeAgentRequestRouter.sanitize(message);

        assertTrue(coarse.length() <= TypeSafeAgentRequestRouter.MAX_SANITIZE_INPUT_CHARS);
        assertTrue(safe.contains("[file attached]"));
        assertFalse(safe.contains("SECRET"));
    }

    @Test
    void sanitizeCapsInputAndRouteCriteriaAreComplete() {
        String safe = TypeSafeAgentRequestRouter.sanitize("x".repeat(5000));

        assertEquals(TypeSafeAgentRequestRouter.MAX_MESSAGE_CHARS, safe.length());
        assertEquals(AgentRequestRouter.Route.values().length,
                TypeSafeAgentRequestRouter.routeCriteria().size());
        assertThrowsOnMutation(TypeSafeAgentRequestRouter.routeCriteria());
    }

    private static JudgmentProvider provider(String choice, double confidence) {
        return new JudgmentProvider() {
            @Override
            public Set<Capability> capabilities() {
                return Set.of(Capability.CHOICE);
            }

            @Override
            public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
                assertEquals(TypeSafeAgentRequestRouter.routeCriteria(), criteria);
                return new ChoiceAnswer(choice, distribution(choice, confidence), confidence);
            }
        };
    }

    private static Map<String, Double> distribution(String choice, double probability) {
        return Map.of(choice, probability);
    }

    private static void assertThrowsOnMutation(Map<String, String> values) {
        boolean threw = false;
        try {
            values.put("OTHER", "other");
        } catch (UnsupportedOperationException expected) {
            threw = true;
        }
        assertTrue(threw);
    }
}
