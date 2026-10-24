package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider.JudgmentException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingAgentRequestRouterTest {

    private static final List<AgentRequestRouter.Route> PACKS = List.of(
            AgentRequestRouter.Route.INSPECT_EXPLAIN, AgentRequestRouter.Route.EDIT_TEST_PLAN,
            AgentRequestRouter.Route.RUN_DIAGNOSE, AgentRequestRouter.Route.CORRELATE,
            AgentRequestRouter.Route.PLAN_FILES);

    @Test
    void requestNearOnePacksExamplesIsFocused() {
        FakeEmbedder embedder = new FakeEmbedder();
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(embedder, 0.75);

        AgentRequestRouter.Decision decision = router.route("route:CORRELATE");

        assertTrue(decision.isFocused());
        assertEquals(AgentRequestRouter.Route.CORRELATE, decision.route());
        assertTrue(decision.confidence() > 0.99);
        assertEquals(5, decision.probabilities().size());
        assertEquals(1.0, decision.probabilities().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    @Test
    void requestBetweenTwoPacksUsesAllTools() {
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(new FakeEmbedder(), 0.75);

        AgentRequestRouter.Decision decision = router.route("between:EDIT_TEST_PLAN:RUN_DIAGNOSE");

        assertEquals(AgentRequestRouter.Outcome.ALL_TOOLS, decision.outcome());
        assertTrue(decision.confidence() < 0.75);
    }

    @Test
    void examplesAreEmbeddedOnceWithTheClassificationPrefix() {
        FakeEmbedder embedder = new FakeEmbedder();
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(embedder, 0.75);

        router.route("route:PLAN_FILES");
        router.route("route:RUN_DIAGNOSE");

        assertEquals(3, embedder.calls.get());
        assertTrue(embedder.seen.stream().allMatch(t -> t.startsWith(EmbeddingAgentRequestRouter.PREFIX)));
        int examples = EmbeddingAgentRequestRouter.routeExamples().values().stream().mapToInt(List::size).sum();
        assertEquals(examples + 2, embedder.seen.size());
    }

    @Test
    void attachmentsAreStrippedBeforeEmbedding() {
        FakeEmbedder embedder = new FakeEmbedder();
        new EmbeddingAgentRequestRouter(embedder, 0.75)
                .route("route:RUN_DIAGNOSE <attached file=\"r.jtl\">secret body</attached>");

        String query = embedder.seen.get(embedder.seen.size() - 1);
        assertFalse(query.contains("secret body"));
        assertTrue(query.contains("[JTL attached]"));
    }

    @Test
    void embeddingFailureIsUnavailableAndRetriedOnTheNextRequest() {
        AtomicInteger calls = new AtomicInteger();
        FakeEmbedder healthy = new FakeEmbedder();
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(inputs -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("Ollama is not reachable");
            }
            return healthy.embed(inputs);
        }, 0.75);

        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, router.route("route:CORRELATE").outcome());
        assertTrue(router.route("route:CORRELATE").isFocused());
    }

    @Test
    void wrongVectorCountsOrSizesAreUnavailable() {
        FakeEmbedder healthy = new FakeEmbedder();
        EmbeddingAgentRequestRouter tooFew = new EmbeddingAgentRequestRouter(inputs -> List.of(new float[]{1f}), 0.75);
        EmbeddingAgentRequestRouter otherSize = new EmbeddingAgentRequestRouter(inputs -> inputs.size() == 1
                ? List.of(new float[]{1f, 0f}) : healthy.embed(inputs), 0.75);
        EmbeddingAgentRequestRouter zero = new EmbeddingAgentRequestRouter(inputs -> inputs.size() == 1
                ? List.of(new float[PACKS.size() + 1]) : healthy.embed(inputs), 0.75);

        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, tooFew.route("route:CORRELATE").outcome());
        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, otherSize.route("route:CORRELATE").outcome());
        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, zero.route("route:CORRELATE").outcome());
        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE,
                new EmbeddingAgentRequestRouter(null, 0.75).route("x").outcome());
    }

    @Test
    void compoundRequestSpanningTwoPacksUsesAllTools() {
        FakeEmbedder embedder = new FakeEmbedder();
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(embedder, 0.75);

        AgentRequestRouter.Decision mixed = router.route("route:PLAN_FILES right now and route:RUN_DIAGNOSE right now");
        AgentRequestRouter.Decision same = router.route("route:PLAN_FILES right now, then route:PLAN_FILES once again");

        assertEquals(AgentRequestRouter.Outcome.ALL_TOOLS, mixed.outcome());
        assertEquals(AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR, mixed.route());
        assertTrue(same.isFocused());
        assertEquals(AgentRequestRouter.Route.PLAN_FILES, same.route());
        assertEquals(3, embedder.calls.get());
    }

    @Test
    void clausesSplitOnlyCompoundRequests() {
        assertEquals(List.of(), EmbeddingAgentRequestRouter.clauses("save the plan"));
        assertEquals(List.of(), EmbeddingAgentRequestRouter.clauses("save and run"));
        assertEquals(List.of(), EmbeddingAgentRequestRouter.clauses("add a json extractor and reuse it"));
        assertEquals(List.of("save the plan", "run the test"),
                EmbeddingAgentRequestRouter.clauses("save the plan and then run the test"));
        assertEquals(EmbeddingAgentRequestRouter.MAX_CLAUSES,
                EmbeddingAgentRequestRouter.clauses("a b c, d e f, g h i, j k l, m n o").size());
    }

    @Test
    void blankRequestUsesAllToolsWithoutCallingTheEmbedder() {
        FakeEmbedder embedder = new FakeEmbedder();

        AgentRequestRouter.Decision decision = new EmbeddingAgentRequestRouter(embedder, 0.75).route("   ");

        assertEquals(AgentRequestRouter.Outcome.ALL_TOOLS, decision.outcome());
        assertEquals(0, embedder.calls.get());
    }

    @Test
    void pendingJudgmentFailurePreservesReasonAndPendingState() {
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(inputs -> {
            throw new JudgmentException("downloading model (5 of 217 MB)", true);
        }, 0.75);

        AgentRequestRouter.Decision decision = router.route("inspect this request");

        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, decision.outcome());
        assertEquals("downloading model (5 of 217 MB)", decision.reason());
        assertTrue(decision.pending());
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionMessage() {
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(inputs -> {
            throw new IllegalStateException("internal detail xyz");
        }, 0.75);

        AgentRequestRouter.Decision decision = router.route("inspect this request");

        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, decision.outcome());
        assertFalse(decision.pending());
        assertFalse(decision.reason().contains("xyz"));
    }

    @Test
    void everyPackHasExamplesAndItsCriteriaButComplexHasNone() {
        Map<AgentRequestRouter.Route, List<String>> examples = EmbeddingAgentRequestRouter.routeExamples();

        assertEquals(PACKS, List.copyOf(examples.keySet()));
        examples.forEach((route, phrases) -> {
            assertTrue(phrases.size() >= 6, route.name());
            assertTrue(phrases.contains(TypeSafeAgentRequestRouter.routeCriteria().get(route.name())), route.name());
        });
    }

    /**
     * One axis per pack: an example points along its own pack's axis; "route:X" points along X,
     * and "between:X:Y" sits halfway between X and Y.
     */
    private static final class FakeEmbedder implements EmbeddingAgentRequestRouter.Embedder {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> seen = new ArrayList<>();
        private final Map<String, AgentRequestRouter.Route> exampleRoutes = new java.util.HashMap<>();

        FakeEmbedder() {
            EmbeddingAgentRequestRouter.routeExamples().forEach((route, phrases) ->
                    phrases.forEach(p -> exampleRoutes.put(EmbeddingAgentRequestRouter.PREFIX + p, route)));
        }

        @Override
        public List<float[]> embed(List<String> inputs) {
            calls.incrementAndGet();
            seen.addAll(inputs);
            List<float[]> out = new ArrayList<>();
            for (String input : inputs) {
                float[] v = new float[PACKS.size() + 1];
                v[PACKS.size()] = 0.01f;
                AgentRequestRouter.Route example = exampleRoutes.get(input);
                String text = input.substring(EmbeddingAgentRequestRouter.PREFIX.length());
                if (example != null) {
                    v[PACKS.indexOf(example)] = 1f;
                } else if (text.startsWith("between:")) {
                    String[] parts = text.split(":");
                    v[PACKS.indexOf(AgentRequestRouter.Route.valueOf(parts[1]))] = 1f;
                    v[PACKS.indexOf(AgentRequestRouter.Route.valueOf(parts[2]))] = 1f;
                } else {
                    String name = text.substring("route:".length()).split(" ")[0];
                    v[PACKS.indexOf(AgentRequestRouter.Route.valueOf(name))] = 1f;
                }
                out.add(v);
            }
            return out;
        }
    }
}
